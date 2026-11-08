package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalSegmentationProbe {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final double VOTE_STEP = 0.01;
  private static final double CHANGE_TOLERANCE = 0.30;

  private record ChangeScore(int truePositive, int falsePositive, int falseNegative,
      List<Double> reference, List<Double> predicted) {
    double precision() { return truePositive + falsePositive == 0 ? 0 : truePositive / (double) (truePositive + falsePositive); }
    double recall() { return truePositive + falseNegative == 0 ? 0 : truePositive / (double) (truePositive + falseNegative); }
    double f1() { return precision() + recall() == 0 ? 0 : 2 * precision() * recall() / (precision() + recall()); }
  }

  private static String digest(Path path) throws Exception {
    var hash = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
    }
    return java.util.HexFormat.of().formatHex(hash.digest());
  }

  private static JsonObject sourceHashes() throws Exception {
    var sources = new LinkedHashMap<String, String>();
    sources.put("runner", "evaluation/src/diagnostic/java/dev/wordbyword/evaluation/LocalSegmentationProbe.java");
    sources.put("change_detector", "evaluation/src/main/java/dev/wordbyword/evaluation/LocalSpeakerChanges.java");
    sources.put("jni_adapter", "evaluation/src/diagnostic/java/dev/wordbyword/evaluation/NativeSegmentation.java");
    sources.put("native_bridge", "evaluation/src/diagnostic/native/segmentation_probe.cc");
    var hashes = new JsonObject();
    for (var source : sources.entrySet()) hashes.addProperty(source.getKey(), digest(Path.of(source.getValue())));
    return hashes;
  }

  private static int[] exclusiveReferenceMasks(JsonArray intervals, double duration) {
    var speakers = new LinkedHashMap<String, Integer>();
    for (var item : intervals) {
      var row = item.getAsJsonObject();
      speakers.computeIfAbsent(row.get("speaker").getAsString(), key -> speakers.size() + 1);
    }
    int frames = (int) Math.ceil(duration / VOTE_STEP);
    int[] masks = new int[frames];
    for (int i = 0; i < frames; i++) {
      double time = (i + 0.5) * VOTE_STEP;
      int active = 0;
      for (var item : intervals) {
        var row = item.getAsJsonObject();
        if (time >= row.get("start").getAsDouble() && time < row.get("end").getAsDouble()) {
          int bit = 1 << speakers.get(row.get("speaker").getAsString());
          active = active == 0 ? bit : active | bit;
        }
      }
      masks[i] = Integer.bitCount(active) == 1 ? active : 0;
    }
    return masks;
  }

  private static ChangeScore score(List<Double> reference, List<Double> predicted) {
    record Pair(int reference, int predicted, double error) {}
    var pairs = new ArrayList<Pair>();
    for (int i = 0; i < reference.size(); i++) for (int j = 0; j < predicted.size(); j++) {
      double error = Math.abs(reference.get(i) - predicted.get(j));
      if (error <= CHANGE_TOLERANCE) pairs.add(new Pair(i, j, error));
    }
    pairs.sort(Comparator.comparingDouble(Pair::error));
    var usedReference = new HashSet<Integer>();
    var usedPredicted = new HashSet<Integer>();
    for (var pair : pairs) if (!usedReference.contains(pair.reference()) && !usedPredicted.contains(pair.predicted())) {
      usedReference.add(pair.reference());
      usedPredicted.add(pair.predicted());
    }
    return new ChangeScore(usedReference.size(), predicted.size() - usedPredicted.size(),
        reference.size() - usedReference.size(), reference, predicted);
  }

  private record Detection(Map<Double, List<LocalSpeakerChanges.Peak>> peaks, int windows,
      int rawChanges, List<Double> rawChangeTimes, Map<Integer, Integer> maskCounts,
      Map<String, Integer> metadata) {}

  private static Detection detect(float[] audio, double duration, NativeSegmentation model) {
    var metadata = model.metadata();
    int sampleRate = metadata.get("sample_rate");
    int windowSize = metadata.get("window_size");
    int fieldSize = metadata.get("receptive_field_size");
    int fieldShift = metadata.get("receptive_field_shift");
    if (sampleRate != 16000 || metadata.get("num_speakers") != 3
        || metadata.get("powerset_max_classes") != 2 || metadata.get("num_classes") != 7)
      throw new IllegalArgumentException("Expected 16 kHz, 3-speaker powerset segmentation metadata: " + metadata);
    int shift = Math.max(1, (int) Math.round(windowSize * 0.1));
    int full = audio.length >= windowSize ? (audio.length - windowSize) / shift + 1 : 0;
    int total = full + (full * shift < audio.length ? 1 : 0);
    int bins = (int) Math.ceil(duration / VOTE_STEP);
    int[] votes = new int[bins], coverage = new int[bins];
    int rawChanges = 0;
    var rawChangeTimes = new ArrayList<Double>();
    var maskCounts = new LinkedHashMap<Integer, Integer>();
    for (int window = 0; window < total; window++) {
      int sampleStart = window * shift;
      int length = Math.min(windowSize, audio.length - sampleStart);
      float[] input = new float[windowSize];
      System.arraycopy(audio, sampleStart, input, 0, length);
      var scores = model.predict(input);
      int[] masks = LocalSpeakerChanges.masks(scores.values(), scores.frames(), scores.classes());
      for (int mask : masks) maskCounts.merge(mask, 1, Integer::sum);
      double start = sampleStart / (double) sampleRate;
      double step = fieldShift / (double) sampleRate;
      double center = start + fieldSize / (2.0 * sampleRate);
      var changes = LocalSpeakerChanges.changes(masks, step, center);
      rawChanges += changes.size();
      rawChangeTimes.addAll(changes);
      double validStart = start + Math.min(1.0, windowSize / (double) sampleRate * 0.1);
      double validEnd = start + windowSize / (double) sampleRate - Math.min(1.0, windowSize / (double) sampleRate * 0.1);
      int first = Math.max(0, (int) Math.ceil(validStart / VOTE_STEP));
      int last = Math.min(bins, (int) Math.floor(validEnd / VOTE_STEP));
      for (int bin = first; bin < last; bin++) coverage[bin]++;
      Set<Integer> windowVotes = new HashSet<>();
      for (double change : changes) {
        int low = Math.max(first, (int) Math.floor((change - 0.15) / VOTE_STEP));
        int high = Math.min(last - 1, (int) Math.ceil((change + 0.15) / VOTE_STEP));
        for (int bin = low; bin <= high; bin++) windowVotes.add(bin);
      }
      for (int bin : windowVotes) votes[bin]++;
    }
    var peaks = new LinkedHashMap<Double, List<LocalSpeakerChanges.Peak>>();
    for (double support : new double[] {0.25, 0.4, 0.5})
      peaks.put(support, LocalSpeakerChanges.peaks(votes, coverage, VOTE_STEP, support));
    return new Detection(peaks, total, rawChanges, rawChangeTimes, maskCounts, metadata);
  }

  private static JsonObject runCase(JsonObject sample, JsonObject oldRow, String ffmpeg,
      Path bridge, Path embedding, Path segmentation) throws Exception {
    double start = sample.get("start_seconds").getAsDouble();
    double end = sample.get("end_seconds").getAsDouble();
    double duration = end - start;
    Path audioPath = Path.of(sample.get("audio").getAsString());
    if (!digest(audioPath).equals(sample.get("audio_sha256").getAsString()))
      throw new IllegalArgumentException("Audio checksum mismatch: " + sample.get("id"));
    float[] audio = DiarizationDiagnostics.decode(ffmpeg, audioPath, start, duration);
    var reference = new ArrayList<SpeakerAttribution.Turn>();
    for (var turn : ScoreDiarization.read(Path.of(sample.get("reference").getAsString()), Double.MAX_VALUE)) {
      double a = Math.max(start, turn.start()), b = Math.min(end, turn.end());
      if (b > a) reference.add(new SpeakerAttribution.Turn(a - start, b - start, turn.speaker()));
    }
    var refJson = JSON.toJsonTree(reference).getAsJsonArray();
    var refChanges = LocalSpeakerChanges.changes(exclusiveReferenceMasks(refJson, duration), VOTE_STEP, VOTE_STEP / 2);
    var result = new JsonObject();
    result.addProperty("id", sample.get("id").getAsString());
    result.addProperty("duration_seconds", duration);
    result.addProperty("reference_exclusive_changes", refChanges.size());
    var modelResult = new JsonObject();
    try (var model = new NativeSegmentation(bridge, segmentation)) {
      var detection = detect(audio, duration, model);
      var sensitivity = new JsonArray();
      for (var entry : detection.peaks().entrySet()) {
        var peakTimes = entry.getValue().stream().map(LocalSpeakerChanges.Peak::seconds).toList();
        var row = new JsonObject();
        row.addProperty("minimum_window_support", entry.getKey());
        row.add("peaks", JSON.toJsonTree(entry.getValue()));
        row.add("boundary_score", JSON.toJsonTree(score(refChanges, peakTimes)));
        sensitivity.add(row);
      }
      modelResult.addProperty("segmentation_sha256", digest(segmentation));
      modelResult.add("metadata", JSON.toJsonTree(detection.metadata()));
      modelResult.addProperty("windows", detection.windows());
      modelResult.addProperty("local_change_events_before_consensus", detection.rawChanges());
      modelResult.add("local_change_times_before_consensus_seconds", JSON.toJsonTree(detection.rawChangeTimes()));
      modelResult.add("mask_frame_counts_across_windows", JSON.toJsonTree(detection.maskCounts()));
      modelResult.add("peak_sensitivity", sensitivity);
    }
    var baseline = oldRow.getAsJsonObject("baseline");
    modelResult.add("baseline_turn_attribution", baseline.get("full_turn_subset_attribution"));
    result.add("model", modelResult);
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 8) throw new IllegalArgumentException(
        "Usage: report.json protocol.json output.json bridge.dylib embedding.onnx ffmpeg modelName=model.onnx[,..] caseId,caseId");
    Path source = Path.of(args[0]), protocolPath = Path.of(args[1]), output = Path.of(args[2]);
    var report = JSON.fromJson(Files.readString(source), JsonObject.class);
    SummarizeDiarizationDiagnostics.summarize(report);
    var protocol = JSON.fromJson(Files.readString(protocolPath), JsonObject.class);
    if (!digest(protocolPath).equals(report.get("protocol_sha256").getAsString()))
      throw new IllegalArgumentException("Protocol does not match cached feature report");
    Path embedding = Path.of(args[4]);
    if (!digest(embedding).equals(report.get("embedding_sha256").getAsString()))
      throw new IllegalArgumentException("Embedding model does not match cached feature report");
    Map<String, JsonObject> oldRows = new LinkedHashMap<>();
    for (var row : report.getAsJsonArray("cases")) oldRows.put(row.getAsJsonObject().getAsJsonObject("case").get("id").getAsString(), row.getAsJsonObject());
    Map<String, JsonObject> samples = new LinkedHashMap<>();
    for (var sample : protocol.getAsJsonArray("cases")) samples.put(sample.getAsJsonObject().get("id").getAsString(), sample.getAsJsonObject());
    var results = new JsonArray();
    var modelFiles = new LinkedHashMap<String, Path>();
    for (String entry : args[6].split(",")) {
      String[] pair = entry.split("=", 2);
      if (pair.length != 2) throw new IllegalArgumentException("Model arguments must be name=path");
      modelFiles.put(pair[0], Path.of(pair[1]));
    }
    var ids = List.of(args[7].split(","));
    for (String id : ids) {
      if (!oldRows.containsKey(id) || !samples.containsKey(id)) throw new IllegalArgumentException("Unknown cached case " + id);
      for (var model : modelFiles.entrySet()) {
        long began = System.nanoTime();
        var row = runCase(samples.get(id), oldRows.get(id), args[5],
            Path.of(args[3]), embedding, model.getValue());
        var metadata = row.getAsJsonObject("model");
        metadata.addProperty("name", model.getKey());
        metadata.addProperty("elapsed_seconds", (System.nanoTime() - began) / 1e9);
        results.add(row);
        System.out.println("DONE " + id + " " + model.getKey() + " seconds=" + metadata.get("elapsed_seconds"));
      }
    }
    var result = new JsonObject();
    result.addProperty("protocol_sha256", report.get("protocol_sha256").getAsString());
    result.addProperty("source_report_sha256", digest(source));
    result.addProperty("embedding_sha256", digest(embedding));
    result.addProperty("bridge_sha256", digest(Path.of(args[3])));
    result.addProperty("complete", true);
    result.add("source_sha256", sourceHashes());
    result.add("native_library_sha256", report.getAsJsonObject("native_library_sha256"));
    result.add("settings", JSON.toJsonTree(Map.of(
        "window_shift_ratio", 0.1,
        "center_margin_seconds", 1.0,
        "minimum_stable_exclusive_run_seconds", 0.15,
        "maximum_silence_or_overlap_gap_seconds", 0.5,
        "consensus_vote_radius_seconds", 0.15,
        "peak_nms_radius_seconds", 0.3,
        "minimum_windows_per_peak", 2,
        "support_thresholds", List.of(0.25, 0.4, 0.5),
        "reference_match_tolerance_seconds", CHANGE_TOLERANCE)));
    result.addProperty("reference_quality", protocol.get("reference_quality").getAsString());
    result.addProperty("change_tolerance_seconds", CHANGE_TOLERANCE);
    result.add("selected_cases", JSON.toJsonTree(ids));
    result.add("segmentation_models", JSON.toJsonTree(modelFiles.entrySet().stream().collect(
        LinkedHashMap::new, (map, entry) -> {
          try { map.put(entry.getKey(), Map.of("path", entry.getValue().toString(), "sha256", digest(entry.getValue()))); }
          catch (Exception error) { throw new RuntimeException(error); }
        }, LinkedHashMap::putAll)));
    result.add("cases", results);
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.writeString(output, JSON.toJson(result) + "\n");
    System.out.println("Wrote " + output);
  }
}
