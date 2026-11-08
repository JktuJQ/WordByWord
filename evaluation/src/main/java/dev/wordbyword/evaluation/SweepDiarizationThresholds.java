package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SweepDiarizationThresholds {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

  static List<SpeakerAttribution.Turn> reference(JsonObject sample, boolean fullOnly) throws Exception {
    double start = sample.get("start_seconds").getAsDouble();
    double end = sample.get("end_seconds").getAsDouble();
    var result = new ArrayList<SpeakerAttribution.Turn>();
    Path path = Path.of(sample.get("reference").getAsString());
    var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)));
    if (!hash.equals(sample.get("reference_sha256").getAsString()))
      throw new IllegalArgumentException("Reference changed since feature extraction");
    for (var turn : ScoreDiarization.read(path, Double.MAX_VALUE)) {
      if ((!fullOnly || turn.start() >= start - 0.001 && turn.end() <= end + 0.001)
          && turn.end() > start && turn.start() < end)
        result.add(new SpeakerAttribution.Turn(Math.max(0, turn.start() - start),
            Math.min(end, turn.end()) - start, turn.speaker()));
    }
    return result;
  }

  static List<SpeakerAttribution.Turn> cluster(JsonArray features, double anchor, double threshold, boolean adaptive, boolean gap, boolean spectral, boolean fullSpectral) {
    var vectors = new ArrayList<double[]>();
    var intervals = new ArrayList<SpeakerAttribution.Turn>();
    var anchors = new ArrayList<double[]>();
    var anchorMask = new ArrayList<Boolean>();
    for (var value : features) {
      var feature = value.getAsJsonObject();
      var interval = feature.getAsJsonObject("interval");
      var turn = new SpeakerAttribution.Turn(interval.get("start").getAsDouble(),
          interval.get("end").getAsDouble(), interval.get("speaker").getAsString());
      double[] vector = feature.getAsJsonArray("vector").asList().stream().mapToDouble(v -> v.getAsDouble()).toArray();
      vectors.add(vector); intervals.add(turn);
      boolean eligible = turn.end() - turn.start() >= anchor
          && !(feature.has("excluded_anchor") && feature.get("excluded_anchor").getAsBoolean());
      anchorMask.add(eligible);
      if (eligible) anchors.add(vector);
    }
    if (anchors.isEmpty()) throw new IllegalArgumentException("No anchor fragments");
    int[] labels = fullSpectral ? SpectralClustering.cluster(anchors, threshold) : EmbeddingClusters.cluster(anchors, spectral ? SpectralSpeakerCount.select(anchors, threshold) : adaptive ? (gap ? AutomaticSpeakerCount.selectGap(anchors, threshold) : AutomaticSpeakerCount.select(anchors, threshold)) : -1, threshold);
    var centroids = new ArrayList<double[]>();
    for (int k = 0; k <= Arrays.stream(labels).max().orElseThrow(); k++) {
      var members = new ArrayList<double[]>();
      for (int i = 0; i < labels.length; i++) if (labels[i] == k) members.add(anchors.get(i));
      centroids.add(EmbeddingClusters.centroid(members));
    }
    var result = new ArrayList<SpeakerAttribution.Turn>();
    int anchorIndex = 0;
    for (int i = 0; i < intervals.size(); i++) {
      int best = 0;
      for (int k = 1; k < centroids.size(); k++)
        if (EmbeddingClusters.distance(vectors.get(i), centroids.get(k))
            < EmbeddingClusters.distance(vectors.get(i), centroids.get(best))) best = k;
      var interval = intervals.get(i);
      if (fullSpectral && anchorMask.get(i)) best = labels[anchorIndex++];
      result.add(new SpeakerAttribution.Turn(interval.start(), interval.end(), "cluster_" + best));
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("Usage: report.json sweep.json threshold1,threshold2,...");
    Path source = Path.of(args[0]);
    var report = JSON.fromJson(Files.readString(source), JsonObject.class);
    SummarizeDiarizationDiagnostics.summarize(report);
    double anchor = report.getAsJsonObject("protocol").getAsJsonObject("settings")
        .get("anchor_seconds").getAsDouble();
    var output = new JsonObject();
    output.add("protocol_sha256", report.get("protocol_sha256"));
    output.add("embedding_sha256", report.get("embedding_sha256"));
    output.addProperty("source_report_sha256", java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
    output.addProperty("policy", "development parameter search; no per-recording reference count supplied; full reference clips scored");
    var trials = new JsonArray();
    for (String number : args[2].split(",")) {
      boolean fullSpectral = number.startsWith("spectral-full@");
      boolean spectral = fullSpectral || number.startsWith("spectral@");
      boolean gap = number.startsWith("gap@");
      boolean adaptive = spectral || gap || number.startsWith("silhouette@");
      String[] parameters = number.split("@");
      double threshold = Double.parseDouble(adaptive ? parameters[1] : number);
      double trialAnchor = adaptive && parameters.length == 3 ? Double.parseDouble(parameters[2]) : anchor;
      if (!Double.isFinite(trialAnchor) || trialAnchor <= 0) throw new IllegalArgumentException("Invalid anchor duration");
      if (!Double.isFinite(threshold) || threshold < 0 || threshold > 2)
        throw new IllegalArgumentException("Invalid cosine-distance threshold");
      for (String features : List.of("oracle_features", "real_features")) {
        var trial = new JsonObject();
        trial.addProperty("selection", fullSpectral ? "spectral_full" : spectral ? "spectral_count" : adaptive ? (gap ? "merge_gap" : "silhouette") : "threshold");
        trial.addProperty("anchor_seconds", trialAnchor);
        trial.addProperty("threshold", threshold); trial.addProperty("features", features);
        var cases = new JsonArray();
        int targetCorrect = 0, targetTurns = 0, targetCount = 0, targetCases = 0, failures = 0;
        double targetReferenceSeconds = 0, targetErrorSeconds = 0;
        for (var value : report.getAsJsonArray("cases")) {
          var row = value.getAsJsonObject();
          var sample = row.getAsJsonObject("case");
          var metrics = new JsonObject(); metrics.addProperty("id", sample.get("id").getAsString());
          try {
            var hyp = cluster(row.getAsJsonArray(features), trialAnchor, threshold, adaptive, gap, spectral, fullSpectral);
            var attribution = SpeakerAttribution.score(reference(sample, true), hyp);
            int count = (int) hyp.stream().map(SpeakerAttribution.Turn::speaker).distinct().count();
            double originalThreshold = report.getAsJsonObject("protocol").getAsJsonObject("settings")
                .get("cluster_threshold").getAsDouble();
            if (!adaptive && Math.abs(threshold - originalThreshold) < 1e-8) {
              String variant = features.equals("oracle_features")
                  ? "reference_clips_threshold" : "baseline_intervals_threshold";
              var original = row.getAsJsonObject("variants").getAsJsonObject(variant);
              if (count != original.get("hypothesis_speakers").getAsInt()
                  || attribution.correct() != original.getAsJsonObject("full_turn_subset_attribution")
                      .get("correct").getAsInt())
                throw new IllegalStateException("Cached sweep disagrees with original inference experiment");
            }
            metrics.add("full_turn_attribution", JSON.toJsonTree(attribution));
            metrics.addProperty("hypothesis_speakers", count);
            metrics.add("hypothesis_intervals", JSON.toJsonTree(hyp));
            double duration = sample.get("end_seconds").getAsDouble() - sample.get("start_seconds").getAsDouble();
            var expected = reference(sample, false).stream().map(t ->
                new ScoreDiarization.Segment(t.start(), t.end(), t.speaker())).toList();
            var actual = hyp.stream().map(t ->
                new ScoreDiarization.Segment(t.start(), t.end(), t.speaker())).toList();
            var sensitivity = new JsonArray();
            for (double radius : new double[] {0, 0.125, 0.25}) {
              var result = ScoreDiarization.score(expected, actual, duration, radius);
              var measure = new JsonObject();
              measure.addProperty("boundary_radius_seconds", radius);
              measure.addProperty("der", result.der());
              measure.add("components", JSON.toJsonTree(result));
              sensitivity.add(measure);
            }
            metrics.add("boundary_sensitivity", sensitivity);
            if (sample.get("role").getAsString().equals("target")) {
              targetCases++; targetCorrect += attribution.correct(); targetTurns += attribution.turns();
              if (count == sample.get("expected_speakers").getAsInt()) targetCount++;
              var seconds = sensitivity.get(0).getAsJsonObject().getAsJsonObject("components");
              targetReferenceSeconds += seconds.get("reference").getAsDouble();
              targetErrorSeconds += seconds.get("missed").getAsDouble()
                  + seconds.get("falseAlarm").getAsDouble() + seconds.get("confusion").getAsDouble();
            }
          } catch (IllegalArgumentException exception) {
            failures++; metrics.addProperty("error", exception.getMessage());
          }
          cases.add(metrics);
        }
        trial.add("cases", cases); trial.addProperty("failed_cases", failures);
        if (failures == 0 && targetTurns > 0) {
          trial.addProperty("target_cases", targetCases);
          trial.addProperty("target_correct_count_cases", targetCount);
          trial.addProperty("target_full_turn_correct", targetCorrect);
          trial.addProperty("target_full_turns", targetTurns);
          trial.addProperty("target_full_turn_accuracy", targetCorrect / (double) targetTurns);
          trial.addProperty("target_der_radius_zero", targetErrorSeconds / targetReferenceSeconds);
        }
        trials.add(trial);
      }
    }
    output.add("trials", trials);
    Path destination = Path.of(args[1]);
    Files.createDirectories(destination.toAbsolutePath().getParent());
    Files.writeString(destination, JSON.toJson(output) + "\n");
    System.out.println("Wrote " + destination);
  }
}
