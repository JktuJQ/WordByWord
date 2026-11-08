package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SummarizeDiarizationDiagnostics {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

  static JsonObject summarize(JsonObject report) {
    if (!report.get("complete").getAsBoolean() || !report.get("all_protocol_cases").getAsBoolean())
      throw new IllegalArgumentException("A complete full-protocol report is required");
    var expected = report.getAsJsonObject("protocol").getAsJsonArray("cases").asList().stream()
        .map(v -> v.getAsJsonObject().get("id").getAsString()).toList();
    var received = report.getAsJsonArray("cases").asList().stream()
        .map(v -> v.getAsJsonObject().getAsJsonObject("case").get("id").getAsString()).toList();
    if (expected.size() != received.size() || received.stream().distinct().count() != received.size()
        || !new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(received)))
      throw new IllegalArgumentException("Case IDs differ from frozen protocol");
    var output = new JsonObject();
    for (String key : List.of("protocol_sha256", "segmentation_sha256", "embedding_sha256",
        "platform", "source_commit", "runner_source_sha256", "clustering_source_sha256",
        "attribution_source_sha256")) output.add(key, report.get(key));
    output.addProperty("interpretation", "development only; repeated sources; no confidence intervals or Android speed claim");
    var cases = new JsonArray();
    for (var value : report.getAsJsonArray("cases")) {
      var row = value.getAsJsonObject();
      if (row.has("error")) throw new IllegalArgumentException("Failed case in complete report");
      var summary = new JsonObject();
      summary.add("case", row.get("case"));
      summary.addProperty("reference_turns", row.get("reference_turns").getAsInt());
      summary.addProperty("full_reference_turns", row.get("full_reference_turns").getAsInt());
      summary.addProperty("baseline_seconds_including_model_load", row.get("baseline_seconds_including_model_load").getAsDouble());
      summary.add("baseline", row.get("baseline"));
      summary.add("variants", row.get("variants"));
      var enrollment = row.getAsJsonObject("oracle_identity_enrollment").deepCopy();
      enrollment.remove("decisions");
      summary.add("oracle_identity_enrollment", enrollment);
      var probes = new JsonArray();
      for (var item : row.getAsJsonArray("duration_probe")) {
        var probe = item.getAsJsonObject().deepCopy();
        for (String key : List.of("same_speaker_distances", "different_speaker_distances")) {
          if (!probe.has(key)) continue;
          var values = probe.remove(key).getAsJsonArray();
          double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
          for (var number : values) {
            double d = number.getAsDouble();
            sum += d; min = Math.min(min, d); max = Math.max(max, d);
          }
          var statistics = new JsonObject();
          statistics.addProperty("pairs", values.size());
          if (!values.isEmpty()) {
            statistics.addProperty("mean", sum / values.size());
            statistics.addProperty("min", min);
            statistics.addProperty("max", max);
          }
          probe.add(key, statistics);
        }
        probes.add(probe);
      }
      summary.add("duration_probe", probes);
      cases.add(summary);
    }
    output.add("cases", cases);
    var aggregate = new JsonArray();
    for (String scope : List.of("target", "control")) {
      var selected = cases.asList().stream().map(v -> v.getAsJsonObject())
          .filter(row -> row.getAsJsonObject("case").get("role").getAsString().equals(scope)).toList();
      for (String name : List.of("baseline", "reference_clips_threshold", "reference_clips_known_count",
          "baseline_intervals_threshold", "baseline_intervals_known_count")) {
        int correct = 0, turns = 0, shortCorrect = 0, shortTurns = 0, correctCount = 0;
        int fullCorrect = 0, fullTurns = 0;
        double total = 0, missed = 0, falseAlarm = 0, confusion = 0;
        var failures = new ArrayList<String>();
        for (var row : selected) {
          var metrics = name.equals("baseline") ? row.getAsJsonObject(name)
              : row.getAsJsonObject("variants").getAsJsonObject(name);
          if (metrics == null || metrics.has("error")) {
            failures.add(row.getAsJsonObject("case").get("id").getAsString());
            continue;
          }
          var attribution = metrics.getAsJsonObject("turn_attribution");
          correct += attribution.get("correct").getAsInt();
          turns += attribution.get("turns").getAsInt();
          shortCorrect += attribution.get("shortCorrect").getAsInt();
          shortTurns += attribution.get("shortTurns").getAsInt();
          var full = metrics.getAsJsonObject("full_turn_subset_attribution");
          fullCorrect += full.get("correct").getAsInt(); fullTurns += full.get("turns").getAsInt();
          if (metrics.get("reference_speakers").getAsInt() == metrics.get("hypothesis_speakers").getAsInt()) correctCount++;
          var seconds = metrics.getAsJsonArray("boundary_sensitivity").get(0)
              .getAsJsonObject().getAsJsonObject("components");
          total += seconds.get("reference").getAsDouble();
          missed += seconds.get("missed").getAsDouble();
          falseAlarm += seconds.get("falseAlarm").getAsDouble();
          confusion += seconds.get("confusion").getAsDouble();
        }
        var result = new JsonObject();
        result.addProperty("scope", scope); result.addProperty("variant", name);
        result.addProperty("expected_cases", selected.size());
        result.add("failed_cases", JSON.toJsonTree(failures));
        result.addProperty("complete", failures.isEmpty());
        if (failures.isEmpty() && !selected.isEmpty()) {
          result.addProperty("turn_accuracy", correct / (double) turns);
          result.addProperty("correct_turns", correct); result.addProperty("turns", turns);
          result.addProperty("full_turn_accuracy", fullCorrect / (double) fullTurns);
          result.addProperty("full_correct_turns", fullCorrect); result.addProperty("full_turns", fullTurns);
          result.addProperty("short_correct", shortCorrect); result.addProperty("short_turns", shortTurns);
          result.addProperty("correct_speaker_count_cases", correctCount);
          result.addProperty("der_radius_zero", (missed + falseAlarm + confusion) / total);
        }
        aggregate.add(result);
      }
    }
    output.add("descriptive_aggregates", aggregate);
    return output;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Usage: report.json summary.json");
    var input = JSON.fromJson(Files.readString(Path.of(args[0])), JsonObject.class);
    var output = Path.of(args[1]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.writeString(output, JSON.toJson(summarize(input)) + "\n");
    System.out.println("Wrote " + output);
  }
}
