package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnchorContamination {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  public record Purity(String dominant, double labelledSeconds, double otherSeconds,
      int speakers, double windowSeconds) {}

  public static Purity profile(double start, double end,
      List<SpeakerAttribution.Turn> reference, double boundaryRadius) {
    var spans = new LinkedHashMap<String, List<double[]>>();
    for (var turn : reference) {
      double from = Math.max(start, turn.start() + boundaryRadius);
      double to = Math.min(end, turn.end() - boundaryRadius);
      if (to > from) spans.computeIfAbsent(turn.speaker(), key -> new ArrayList<>())
          .add(new double[] {from, to});
    }
    String dominant = null; double total = 0, largest = 0;
    for (var entry : spans.entrySet()) {
      entry.getValue().sort(java.util.Comparator.comparingDouble(a -> a[0]));
      double last = Double.NEGATIVE_INFINITY, sum = 0;
      for (var span : entry.getValue()) {
        sum += Math.max(0, span[1] - Math.max(last, span[0]));
        last = Math.max(last, span[1]);
      }
      total += sum;
      if (sum > largest + 1e-8) { largest = sum; dominant = entry.getKey(); }
      else if (Math.abs(sum - largest) <= 1e-8) dominant = null;
    }
    return new Purity(dominant, total, total - largest, spans.size(), end - start);
  }

  private static double[] vector(JsonObject feature) {
    return feature.getAsJsonArray("vector").asList().stream().mapToDouble(v -> v.getAsDouble()).toArray();
  }

  private static SpeakerAttribution.Turn interval(JsonObject feature) {
    var t = feature.getAsJsonObject("interval");
    return new SpeakerAttribution.Turn(t.get("start").getAsDouble(), t.get("end").getAsDouble(),
        t.get("speaker").getAsString());
  }

  private static List<SpeakerAttribution.Turn> centroidOnly(JsonArray features,
      double anchor, double threshold, JsonObject diagnostics) {
    var anchors = features.asList().stream().map(v -> v.getAsJsonObject())
        .filter(f -> interval(f).end() - interval(f).start() >= anchor).toList();
    int[] labels = EmbeddingClusters.cluster(anchors.stream().map(AnchorContamination::vector).toList(), -1, threshold);
    var centroids = new ArrayList<double[]>(); int fallbacks = 0;
    for (int group = 0; group <= Arrays.stream(labels).max().orElseThrow(); group++) {
      var all = new ArrayList<double[]>(); var clean = new ArrayList<double[]>();
      for (int i = 0; i < labels.length; i++) if (labels[i] == group) {
        var f = anchors.get(i); all.add(vector(f));
        if (!f.get("excluded_anchor").getAsBoolean()) clean.add(vector(f));
      }
      if (clean.isEmpty()) { clean = all; fallbacks++; }
      centroids.add(EmbeddingClusters.centroid(clean));
    }
    diagnostics.addProperty("clusters_without_clean_anchor_kept_original", fallbacks);
    var result = new ArrayList<SpeakerAttribution.Turn>();
    for (var value : features) {
      var f = value.getAsJsonObject(); var t = interval(f); double[] v = vector(f); int best = 0;
      for (int i = 1; i < centroids.size(); i++)
        if (EmbeddingClusters.distance(v, centroids.get(i)) < EmbeddingClusters.distance(v, centroids.get(best))) best = i;
      result.add(new SpeakerAttribution.Turn(t.start(), t.end(), "cluster_" + best));
    }
    return result;
  }

  private static JsonObject score(List<SpeakerAttribution.Turn> full,
      List<SpeakerAttribution.Turn> all, List<SpeakerAttribution.Turn> hypothesis, double duration) {
    var result = new JsonObject();
    result.add("full_turn_attribution", JSON.toJsonTree(SpeakerAttribution.score(full, hypothesis)));
    var expected = all.stream().map(t -> new ScoreDiarization.Segment(t.start(), t.end(), t.speaker())).toList();
    var actual = hypothesis.stream().map(t -> new ScoreDiarization.Segment(t.start(), t.end(), t.speaker())).toList();
    var der = ScoreDiarization.score(expected, actual, duration);
    result.addProperty("der", der.der()); result.add("components", JSON.toJsonTree(der));
    result.addProperty("hypothesis_speakers", hypothesis.stream().map(SpeakerAttribution.Turn::speaker).distinct().count());
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Usage: report.json output.json");
    Path input = Path.of(args[0]); var report = JSON.fromJson(Files.readString(input), JsonObject.class);
    SummarizeDiarizationDiagnostics.summarize(report);
    var settings = report.getAsJsonObject("protocol").getAsJsonObject("settings");
    double anchor = settings.get("anchor_seconds").getAsDouble();
    double maximum = settings.get("max_embedding_seconds").getAsDouble();
    double threshold = 0.45;
    var output = new JsonObject(); output.addProperty("policy", "reference-assisted diagnostic; no inference; source clip identity not acoustic truth");
    output.addProperty("threshold", threshold); output.add("protocol_sha256", report.get("protocol_sha256"));
    output.add("embedding_sha256", report.get("embedding_sha256"));
    output.addProperty("source_report_sha256", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
    long expectedTargets = report.getAsJsonObject("protocol").getAsJsonArray("cases").asList().stream()
        .filter(v -> v.getAsJsonObject().get("role").getAsString().equals("target")).count();
    var cases = new JsonArray(); var totals = new LinkedHashMap<String, double[]>();
    for (var value : report.getAsJsonArray("cases")) {
      var row = value.getAsJsonObject(); var sample = row.getAsJsonObject("case");
      var all = SweepDiarizationThresholds.reference(sample, false);
      var full = SweepDiarizationThresholds.reference(sample, true);
      double duration = sample.get("end_seconds").getAsDouble() - sample.get("start_seconds").getAsDouble();
      var features = row.getAsJsonArray("real_features").deepCopy();
      var baseline = SweepDiarizationThresholds.cluster(features, anchor, threshold, false, false, false, false);
      var mapping = SpeakerAttribution.score(full, baseline).mapping();
      var profiles = new JsonArray(); var strata = new LinkedHashMap<String, int[]>();
      int index = 0;
      for (var item : features) {
        var f = item.getAsJsonObject(); var t = interval(f);
        double length = Math.min(maximum, t.end() - t.start());
        double from = t.start() + (t.end() - t.start() - length) / 2;
        var crop = profile(from, from + length, all, 0);
        var core = profile(from, from + length, all, 0.125);
        var whole = profile(t.start(), t.end(), all, 0);
        boolean strict = crop.otherSeconds() > 1e-6;
        boolean material = core.otherSeconds() >= 0.2;
        f.addProperty("strict_mixed", strict); f.addProperty("material_mixed", material);
        boolean isAnchor = t.end() - t.start() >= anchor;
        String category = crop.dominant() == null ? "unlabelled" : material ? "mixed_material" : strict ? "mixed_boundary_only" : "single_source_voice";
        String prediction = mapping.get(baseline.get(index).speaker());
        int[] counts = strata.computeIfAbsent(category, key -> new int[4]);
        counts[0]++; if (isAnchor) counts[1]++;
        if (crop.dominant() != null) { counts[2]++; if (!crop.dominant().equals(prediction)) counts[3]++; }
        var entry = new JsonObject(); entry.addProperty("feature", index++); entry.addProperty("anchor", isAnchor);
        entry.add("interval", JSON.toJsonTree(t)); entry.add("whole", JSON.toJsonTree(whole));
        entry.add("embedding_crop", JSON.toJsonTree(crop)); entry.add("embedding_crop_core", JSON.toJsonTree(core));
        entry.addProperty("prediction_after_global_mapping", prediction); profiles.add(entry);
      }
      var result = new JsonObject(); result.add("case", sample); result.add("profiles", profiles);
      result.addProperty("strata_fields", "features,anchors,labelled_features,dominant_crop_voice_errors; one global baseline mapping");
      result.add("strata", JSON.toJsonTree(strata));
      var decisions = new JsonArray(); var clipStrata = new LinkedHashMap<String, int[]>();
      int hits = 0;
      for (var clip : full) {
        String predicted = profile(clip.start(), clip.end(), baseline, 0).dominant();
        boolean correct = predicted != null && clip.speaker().equals(mapping.get(predicted));
        if (correct) hits++;
        boolean touchesMixedSpan = false, touchesMixedCrop = false;
        for (var item : profiles) {
          var f = item.getAsJsonObject(); var t = f.getAsJsonObject("interval");
          if (Math.min(clip.end(), t.get("end").getAsDouble()) <= Math.max(clip.start(), t.get("start").getAsDouble())) continue;
          if (f.getAsJsonObject("whole").get("speakers").getAsInt() > 1) touchesMixedSpan = true;
          if (f.getAsJsonObject("embedding_crop_core").get("otherSeconds").getAsDouble() >= 0.2) touchesMixedCrop = true;
        }
        var decision = new JsonObject(); decision.add("reference", JSON.toJsonTree(clip));
        decision.addProperty("correct", correct); decision.addProperty("short", clip.end() - clip.start() < 2);
        decision.addProperty("touches_mixed_span", touchesMixedSpan);
        decision.addProperty("touches_material_mixed_embedding", touchesMixedCrop); decisions.add(decision);
        for (String category : List.of("all", clip.end() - clip.start() < 2 ? "short" : "long",
            touchesMixedSpan ? "touches_mixed_span" : "only_single_voice_spans",
            touchesMixedCrop ? "touches_mixed_embedding" : "no_mixed_embedding")) {
          int[] counts = clipStrata.computeIfAbsent(category, key -> new int[2]); counts[0]++; if (!correct) counts[1]++;
        }
      }
      if (hits != SpeakerAttribution.score(full, baseline).correct())
        throw new IllegalStateException("Clip error breakdown disagrees with global attribution score");
      result.addProperty("clip_strata_fields", "full_clips,errors; overlapping strata; global mapping fixed");
      result.add("clip_strata", JSON.toJsonTree(clipStrata)); result.add("clip_decisions", decisions);
      var variants = new JsonObject();
      for (String mode : List.of("baseline", "strict_centroids", "material_centroids", "material_refit")) {
        for (var item : features) {
          var f = item.getAsJsonObject();
          f.addProperty("excluded_anchor", !mode.equals("baseline") && f.get(mode.startsWith("strict") ? "strict_mixed" : "material_mixed").getAsBoolean());
        }
        var details = new JsonObject(); List<SpeakerAttribution.Turn> hypothesis;
        try {
          if (mode.equals("baseline")) hypothesis = baseline;
          else if (mode.endsWith("refit")) hypothesis = SweepDiarizationThresholds.cluster(features, anchor, threshold, false, false, false, false);
          else hypothesis = centroidOnly(features, anchor, threshold, details);
          var metrics = score(full, all, hypothesis, duration); metrics.add("diagnostics", details);
          variants.add(mode, metrics);
          if (sample.get("role").getAsString().equals("target")) {
            double[] sums = totals.computeIfAbsent(mode, key -> new double[6]);
            var attribution = metrics.getAsJsonObject("full_turn_attribution");
            sums[0] += attribution.get("correct").getAsInt(); sums[1] += attribution.get("turns").getAsInt();
            var components = metrics.getAsJsonObject("components"); sums[2] += components.get("reference").getAsDouble();
            sums[3] += components.get("missed").getAsDouble() + components.get("falseAlarm").getAsDouble() + components.get("confusion").getAsDouble();
            sums[4]++; if (metrics.get("hypothesis_speakers").getAsInt() == sample.get("expected_speakers").getAsInt()) sums[5]++;
          }
        } catch (IllegalArgumentException exception) {
          details.addProperty("error", exception.toString()); variants.add(mode, details);
        }
      }
      result.add("variants", variants); cases.add(result);
    }
    var aggregates = new JsonObject();
    totals.forEach((mode, sums) -> {
      var metric = new JsonObject(); metric.addProperty("scored_cases", (int) sums[4]);
      if (sums[4] == expectedTargets) {
        metric.addProperty("correct", (int) sums[0]); metric.addProperty("turns", (int) sums[1]);
        metric.addProperty("der", sums[3] / sums[2]); metric.addProperty("correct_count_cases", (int) sums[5]);
      }
      aggregates.add(mode, metric);
    });
    output.add("target_aggregates", aggregates); output.add("cases", cases);
    Path destination = Path.of(args[1]); Files.createDirectories(destination.toAbsolutePath().getParent());
    Files.writeString(destination, JSON.toJson(output) + "\n"); System.out.println("Wrote " + destination);
  }
}
