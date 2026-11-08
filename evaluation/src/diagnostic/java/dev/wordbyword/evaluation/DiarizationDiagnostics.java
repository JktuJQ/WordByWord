package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class DiarizationDiagnostics {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private record Feature(int turn, SpeakerAttribution.Turn interval, double[] vector) {}

  private final SpeakerEmbeddingExtractor extractor;
  private final JsonObject settings;
  private final double minEmbedding, anchor, maxEmbedding, threshold;

  private DiarizationDiagnostics(SpeakerEmbeddingExtractor extractor, JsonObject settings) {
    this.extractor = extractor;
    this.settings = settings;
    minEmbedding = settings.get("minimum_embedding_seconds").getAsDouble();
    anchor = settings.get("anchor_seconds").getAsDouble();
    maxEmbedding = settings.get("max_embedding_seconds").getAsDouble();
    threshold = settings.get("cluster_threshold").getAsDouble();
  }

  private double[] embed(float[] samples, double start, double end, double limit) {
    double length = Math.min(end - start, limit);
    int from = Math.max(0, (int) Math.round((start + (end - start - length) / 2) * 16000));
    int to = Math.min(samples.length, from + (int) Math.round(length * 16000));
    if (to <= from) throw new IllegalArgumentException("Empty embedding interval");
    var stream = extractor.createStream();
    try {
      stream.acceptWaveform(Arrays.copyOfRange(samples, from, to), 16000);
      stream.inputFinished();
      float[] vector = extractor.compute(stream);
      double[] values = new double[vector.length];
      for (int i = 0; i < vector.length; i++) values[i] = vector[i];
      return EmbeddingClusters.normalize(values);
    } finally {
      stream.release();
    }
  }

  private List<Feature> features(float[] samples, List<SpeakerAttribution.Turn> intervals) {
    var result = new ArrayList<Feature>();
    for (int i = 0; i < intervals.size(); i++) {
      var turn = intervals.get(i);
      if (turn.end() - turn.start() < minEmbedding) continue;
      result.add(new Feature(i, turn, embed(samples, turn.start(), turn.end(), maxEmbedding)));
    }
    return result;
  }

  private List<SpeakerAttribution.Turn> recluster(
      List<SpeakerAttribution.Turn> intervals, List<Feature> features, int count) {
    var anchors = features.stream()
        .filter(f -> f.interval().end() - f.interval().start() >= anchor).toList();
    if (anchors.isEmpty() || count > anchors.size())
      throw new IllegalArgumentException("Insufficient anchor turns for clustering");
    int[] labels = EmbeddingClusters.cluster(anchors.stream().map(Feature::vector).toList(), count, threshold);
    int groups = Arrays.stream(labels).max().orElseThrow() + 1;
    var centroids = new ArrayList<double[]>();
    for (int group = 0; group < groups; group++) {
      var members = new ArrayList<double[]>();
      for (int i = 0; i < labels.length; i++) if (labels[i] == group) members.add(anchors.get(i).vector());
      centroids.add(EmbeddingClusters.centroid(members));
    }
    var result = new ArrayList<SpeakerAttribution.Turn>();
    for (int i = 0; i < intervals.size(); i++) {
      final int index = i;
      var candidates = features.stream().filter(f -> f.turn() == index).toList();
      var turn = intervals.get(i);
      if (candidates.isEmpty()) continue;
      double[] vector = candidates.get(0).vector();
      int best = nearest(centroids, vector);
      result.add(new SpeakerAttribution.Turn(turn.start(), turn.end(), "cluster_" + best));
    }
    return result;
  }

  private static int nearest(List<double[]> centroids, double[] vector) {
    int winner = 0;
    for (int j = 1; j < centroids.size(); j++)
      if (EmbeddingClusters.distance(centroids.get(j), vector)
          < EmbeddingClusters.distance(centroids.get(winner), vector)) winner = j;
    return winner;
  }

  private JsonObject enrollment(List<Feature> features) {
    var expected = new ArrayList<String>();
    var actual = new ArrayList<String>();
    var shortMask = new ArrayList<Boolean>();
    var skipped = new ArrayList<Integer>();
    var decisions = new JsonArray();
    for (var query : features) {
      var groups = new LinkedHashMap<String, List<double[]>>();
      for (var other : features) {
        if (other.turn() == query.turn()) continue;
        if (other.interval().end() - other.interval().start() >= anchor)
          groups.computeIfAbsent(other.interval().speaker(), key -> new ArrayList<>()).add(other.vector());
      }
      if (!groups.containsKey(query.interval().speaker())) { skipped.add(query.turn()); continue; }
      var names = new ArrayList<>(groups.keySet());
      var centroids = groups.values().stream().map(EmbeddingClusters::centroid).toList();
      String prediction = names.get(nearest(centroids, query.vector()));
      expected.add(query.interval().speaker());
      actual.add(prediction);
      shortMask.add(query.interval().end() - query.interval().start() < 2);
      var decision = new JsonObject();
      decision.addProperty("turn", query.turn());
      decision.addProperty("reference", query.interval().speaker());
      decision.addProperty("prediction", prediction);
      decisions.add(decision);
    }
    int correct = 0, shortCount = 0, shortCorrect = 0;
    var recallCounts = new LinkedHashMap<String, int[]>();
    for (int i = 0; i < expected.size(); i++) {
      boolean hit = expected.get(i).equals(actual.get(i));
      int[] counts = recallCounts.computeIfAbsent(expected.get(i), key -> new int[2]);
      counts[0]++;
      if (hit) { correct++; counts[1]++; }
      if (shortMask.get(i)) { shortCount++; if (hit) shortCorrect++; }
    }
    var result = new JsonObject();
    result.addProperty("policy", "reference-labelled enrollment; leave entire query turn out; diagnostic only");
    result.addProperty("eligible_turns", expected.size());
    result.addProperty("correct", correct);
    result.addProperty("short_turns", shortCount);
    result.addProperty("short_correct", shortCorrect);
    result.add("per_speaker_counts_total_correct", JSON.toJsonTree(recallCounts));
    result.add("skipped_no_other_anchor", JSON.toJsonTree(skipped));
    result.add("decisions", decisions);
    return result;
  }

  private JsonArray durationProbe(float[] samples, List<SpeakerAttribution.Turn> reference) {
    double longest = settings.getAsJsonArray("duration_probe_seconds").asList().stream()
        .mapToDouble(v -> v.getAsDouble()).max().orElseThrow();
    var eligible = new ArrayList<SpeakerAttribution.Turn>();
    for (var turn : reference) {
      boolean overlap = reference.stream().anyMatch(other -> other != turn
          && Math.min(turn.end(), other.end()) > Math.max(turn.start(), other.start()));
      if (!overlap && turn.end() - turn.start() >= longest) eligible.add(turn);
    }
    var result = new JsonArray();
    int count = (int) eligible.stream().map(SpeakerAttribution.Turn::speaker).distinct().count();
    for (var value : settings.getAsJsonArray("duration_probe_seconds")) {
      double seconds = value.getAsDouble();
      long began = System.nanoTime();
      var vectors = eligible.stream().map(t -> embed(samples, t.start(), t.end(), seconds)).toList();
      var row = new JsonObject();
      row.addProperty("seconds", seconds);
      row.addProperty("same_eligible_turns", eligible.size());
      row.addProperty("eligible_speakers", count);
      row.addProperty("all_full_turns", reference.size());
      row.addProperty("embedding_seconds", elapsed(began));
      if (!vectors.isEmpty()) {
        int[] labels = EmbeddingClusters.cluster(vectors, count, threshold);
        var expected = eligible.stream().map(SpeakerAttribution.Turn::speaker).toList();
        var actual = Arrays.stream(labels).mapToObj(i -> "c" + i).toList();
        row.add("known_count_attribution", JSON.toJsonTree(SpeakerAttribution.scoreLabels(
            expected, actual, eligible.stream().map(t -> false).toList())));
        var same = new ArrayList<Double>();
        var different = new ArrayList<Double>();
        for (int i = 0; i < vectors.size(); i++) for (int j = i + 1; j < vectors.size(); j++) {
          double distance = EmbeddingClusters.distance(vectors.get(i), vectors.get(j));
          (expected.get(i).equals(expected.get(j)) ? same : different).add(distance);
        }
        row.add("same_speaker_distances", JSON.toJsonTree(same));
        row.add("different_speaker_distances", JSON.toJsonTree(different));
      }
      result.add(row);
    }
    return result;
  }

  private JsonObject evaluate(
      List<SpeakerAttribution.Turn> reference, List<SpeakerAttribution.Turn> hypothesis, double duration) {
    var result = new JsonObject();
    result.add("turn_attribution", JSON.toJsonTree(SpeakerAttribution.score(reference, hypothesis)));
    result.addProperty("reference_speakers", reference.stream().map(SpeakerAttribution.Turn::speaker).distinct().count());
    result.addProperty("hypothesis_speakers", hypothesis.stream().map(SpeakerAttribution.Turn::speaker).distinct().count());
    var sensitivity = new JsonArray();
    for (var value : settings.getAsJsonArray("boundary_radius_seconds")) {
      double radius = value.getAsDouble();
      var score = ScoreDiarization.score(segments(reference), segments(hypothesis), duration, radius);
      var row = new JsonObject();
      row.addProperty("boundary_radius_seconds", radius);
      row.addProperty("der", score.der());
      row.add("components", JSON.toJsonTree(score));
      row.addProperty("reference_time_retained_fraction", score.reference()
          / ScoreDiarization.score(segments(reference), segments(reference), duration).reference());
      sensitivity.add(row);
    }
    result.add("boundary_sensitivity", sensitivity);
    return result;
  }

  private static List<ScoreDiarization.Segment> segments(List<SpeakerAttribution.Turn> turns) {
    return turns.stream().map(t -> new ScoreDiarization.Segment(t.start(), t.end(), t.speaker())).toList();
  }

  private JsonObject run(JsonObject sample, Path segmentation, SpeakerEmbeddingExtractorConfig embedding,
      String ffmpeg, Path output) throws Exception {
    Path audio = Path.of(sample.get("audio").getAsString());
    Path referenceFile = Path.of(sample.get("reference").getAsString());
    if (!digest(audio).equals(sample.get("audio_sha256").getAsString())
        || !digest(referenceFile).equals(sample.get("reference_sha256").getAsString()))
      throw new IllegalArgumentException("Input checksum mismatch");
    double start = sample.get("start_seconds").getAsDouble();
    double end = sample.get("end_seconds").getAsDouble();
    double duration = end - start;
    if (start < 0 || duration <= 0 || duration > 300)
      throw new IllegalArgumentException("Expected a window of up to 300 seconds");
    var all = ScoreDiarization.read(referenceFile, Double.MAX_VALUE);
    var reference = new ArrayList<SpeakerAttribution.Turn>();
    var fullTurns = new ArrayList<SpeakerAttribution.Turn>();
    for (var turn : all) {
      double a = Math.max(start, turn.start()), b = Math.min(end, turn.end());
      if (b > a) {
        var clipped = new SpeakerAttribution.Turn(a - start, b - start, turn.speaker());
        reference.add(clipped);
        if (turn.start() >= start - 0.001 && turn.end() <= end + 0.001) fullTurns.add(clipped);
      }
    }
    float[] samples = decode(ffmpeg, audio, start, duration);
    double decodedDuration = samples.length / 16000.0;
    if (Math.abs(decodedDuration - duration) > 0.002)
      throw new IllegalArgumentException("Decoded duration differs from requested window");
    var result = new JsonObject();
    result.add("case", sample.deepCopy());
    result.addProperty("reference_turns", reference.size());
    result.addProperty("full_reference_turns", fullTurns.size());
    result.addProperty("decoded_pcm_sha256", digestFloats(samples));
    long began = System.nanoTime();
    var pyannote = OfflineSpeakerSegmentationPyannoteModelConfig.builder()
        .setModel(segmentation.toString()).setWindowShiftRatio(0.1f).build();
    var seg = OfflineSpeakerSegmentationModelConfig.builder().setPyannote(pyannote)
        .setNumThreads(1).setDebug(false).setProvider("cpu").build();
    var config = OfflineSpeakerDiarizationConfig.builder().setSegmentation(seg).setEmbedding(embedding)
        .setClustering(FastClusteringConfig.builder().setThreshold((float) threshold).setNumClusters(-1).build())
        .setMinDurationOn(0.3f).setMinDurationOff(0.5f).build();
    var baseline = new ArrayList<SpeakerAttribution.Turn>();
    var diarizer = new OfflineSpeakerDiarization(config);
    try {
      for (var turn : diarizer.process(samples))
        baseline.add(new SpeakerAttribution.Turn(Math.max(0, turn.getStart()),
            Math.min(duration, turn.getEnd()), "speaker_" + turn.getSpeaker()));
    } finally { diarizer.release(); }
    result.addProperty("baseline_seconds_including_model_load", elapsed(began));
    result.add("baseline", evaluate(reference, baseline, duration));
    result.getAsJsonObject("baseline").add("full_turn_subset_attribution",
        JSON.toJsonTree(SpeakerAttribution.score(fullTurns, baseline)));
    saveRttm(output.resolve("baseline.rttm"), baseline);
    int count = (int) reference.stream().map(SpeakerAttribution.Turn::speaker).distinct().count();
    if (count != sample.get("expected_speakers").getAsInt())
      throw new IllegalArgumentException("Reference speaker count differs from frozen protocol");
    began = System.nanoTime();
    var oracleFeatures = features(samples, fullTurns);
    var realFeatures = features(samples, baseline);
    result.addProperty("extra_embedding_seconds", elapsed(began));
    result.add("oracle_features", JSON.toJsonTree(oracleFeatures));
    result.add("real_features", JSON.toJsonTree(realFeatures));
    result.add("oracle_identity_enrollment", enrollment(oracleFeatures));
    var variants = new JsonObject();
    for (boolean oracle : List.of(true, false)) {
      var intervals = oracle ? fullTurns : baseline;
      var features = oracle ? oracleFeatures : realFeatures;
      for (int k : new int[] {-1, count}) {
        String name = (oracle ? "reference_clips" : "baseline_intervals")
            + (k == -1 ? "_threshold" : "_known_count");
        try {
          var hyp = recluster(intervals, features, k);
          var metrics = evaluate(reference, hyp, duration);
          metrics.add("full_turn_subset_attribution",
              JSON.toJsonTree(SpeakerAttribution.score(fullTurns, hyp)));
          metrics.addProperty("diagnostic_uses_reference_count", k != -1);
          variants.add(name, metrics);
          saveRttm(output.resolve(name + ".rttm"), hyp);
        } catch (IllegalArgumentException exception) {
          var failure = new JsonObject();
          failure.addProperty("error", exception.getMessage());
          variants.add(name, failure);
        }
      }
    }
    result.add("variants", variants);
    result.add("duration_probe", durationProbe(samples, fullTurns));
    return result;
  }

  static float[] decode(String ffmpeg, Path file, double start, double duration) throws Exception {
    var process = new ProcessBuilder(ffmpeg, "-v", "error", "-nostdin", "-ss", Double.toString(start),
        "-i", file.toString(), "-t", Double.toString(duration), "-ac", "1", "-ar", "16000",
        "-f", "f32le", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] bytes = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0 || bytes.length % 4 != 0)
      throw new IllegalStateException("Audio decoding failed");
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    float[] result = new float[buffer.remaining()];
    buffer.get(result);
    return result;
  }

  private static String digestFloats(float[] values) throws Exception {
    var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.asFloatBuffer().put(values);
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array()));
  }

  private static String digest(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static double elapsed(long began) { return (System.nanoTime() - began) / 1e9; }

  private static void saveRttm(Path path, List<SpeakerAttribution.Turn> intervals) throws Exception {
    var text = new StringBuilder();
    for (var turn : intervals) text.append(String.format(Locale.ROOT,
        "SPEAKER diagnostic 1 %.6f %.6f <NA> <NA> %s <NA> <NA>%n",
        turn.start(), turn.end() - turn.start(), turn.speaker()));
    Files.writeString(path, text);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 5 || args.length > 6)
      throw new IllegalArgumentException("Usage: protocol.json output_dir segmentation.onnx embedding.onnx ffmpeg [case_id]");
    Path protocolPath = Path.of(args[0]), output = Path.of(args[1]);
    Path segmentation = Path.of(args[2]).toAbsolutePath(), embedding = Path.of(args[3]).toAbsolutePath();
    var protocol = JSON.fromJson(Files.readString(protocolPath), JsonObject.class);
    Files.createDirectories(output);
    var report = new JsonObject();
    report.addProperty("started_at", Instant.now().toString());
    report.addProperty("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.addProperty("java", System.getProperty("java.version"));
    report.addProperty("java_library_path", System.getProperty("java.library.path"));
    report.addProperty("sherpa_jar_sha256", digest(Path.of(SpeakerEmbeddingExtractor.class
        .getProtectionDomain().getCodeSource().getLocation().toURI())));
    report.addProperty("segmentation_sha256", digest(segmentation));
    report.addProperty("embedding_sha256", digest(embedding));
    report.addProperty("segmentation_path", segmentation.toString());
    report.addProperty("embedding_path", embedding.toString());
    report.addProperty("runner_source_sha256", digest(Path.of(
        "evaluation/src/diagnostic/java/dev/wordbyword/evaluation/DiarizationDiagnostics.java")));
    report.addProperty("clustering_source_sha256", digest(Path.of(
        "evaluation/src/main/java/dev/wordbyword/evaluation/EmbeddingClusters.java")));
    report.addProperty("attribution_source_sha256", digest(Path.of(
        "evaluation/src/main/java/dev/wordbyword/evaluation/SpeakerAttribution.java")));
    var nativeHashes = new JsonObject();
    for (String directory : System.getProperty("java.library.path").split(java.io.File.pathSeparator)) {
      if (directory.isBlank() || !Files.isDirectory(Path.of(directory))) continue;
      try (var files = Files.list(Path.of(directory))) {
        for (var path : files.filter(Files::isRegularFile).toList())
          nativeHashes.addProperty(path.toAbsolutePath().toString(), digest(path));
      }
    }
    report.add("native_library_sha256", nativeHashes);
    var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
    report.addProperty("source_commit", new String(git.getInputStream().readAllBytes()).trim());
    if (git.waitFor() != 0) throw new IllegalStateException("Cannot identify Git revision");
    var ffmpeg = new ProcessBuilder(args[4], "-version").start();
    report.addProperty("ffmpeg_version", new String(ffmpeg.getInputStream().readAllBytes()).split("\n")[0]);
    if (ffmpeg.waitFor() != 0) throw new IllegalStateException("Cannot identify FFmpeg version");
    report.addProperty("protocol_sha256", digest(protocolPath));
    report.add("protocol", protocol.deepCopy());
    report.addProperty("complete", false);
    report.addProperty("all_protocol_cases", args.length == 5);
    var rows = new JsonArray();
    report.add("cases", rows);
    Path reportPath = output.resolve("report.json");
    Files.writeString(reportPath, JSON.toJson(report));
    var config = SpeakerEmbeddingExtractorConfig.builder().setModel(embedding.toString())
        .setNumThreads(1).setDebug(false).setProvider("cpu").build();
    var extractor = new SpeakerEmbeddingExtractor(config);
    int failures = 0;
    try {
      var runner = new DiarizationDiagnostics(extractor, protocol.getAsJsonObject("settings"));
      for (var value : protocol.getAsJsonArray("cases")) {
        var sample = value.getAsJsonObject();
        String id = sample.get("id").getAsString();
        if (args.length == 6 && !id.equals(args[5])) continue;
        long began = System.nanoTime();
        Path directory = output.resolve(id);
        Files.createDirectories(directory);
        try {
          System.out.println("START " + id);
          var row = runner.run(sample, segmentation, config, args[4], directory);
          row.addProperty("elapsed_seconds", elapsed(began));
          rows.add(row);
          Files.writeString(directory.resolve("case.json"), JSON.toJson(row));
          System.out.println("DONE " + id + " seconds=" + elapsed(began));
        } catch (Exception exception) {
          failures++;
          var row = new JsonObject();
          row.add("case", sample);
          row.addProperty("error", exception.toString());
          rows.add(row);
          System.err.println("FAILED " + id + ": " + exception);
        }
        Files.writeString(reportPath, JSON.toJson(report));
      }
    } finally { extractor.release(); }
    report.addProperty("failed_cases", failures);
    report.addProperty("complete", failures == 0 && !rows.isEmpty());
    report.addProperty("finished_at", Instant.now().toString());
    Files.writeString(reportPath, JSON.toJson(report));
    if (failures > 0 || rows.isEmpty()) throw new IllegalStateException("Incomplete diagnostics");
  }
}
