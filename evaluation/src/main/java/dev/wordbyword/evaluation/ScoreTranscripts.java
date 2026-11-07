package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScoreTranscripts {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

  record Turn(String speaker, long start, long end, String text) {}

  record Assignment(int errors, int[] columns) {}

  static List<Turn> read(JsonObject document, double duration) {
    if (!Double.isFinite(duration) || duration <= 0)
      throw new IllegalArgumentException("Invalid duration");
    if (!document.has("transcript") || !document.get("transcript").isJsonArray())
      throw new IllegalArgumentException("Expected transcript array");
    var turns = new ArrayList<Turn>();
    for (var item : document.getAsJsonArray("transcript")) {
      var row = item.getAsJsonObject();
      String speaker = row.get("speaker_id").getAsString();
      String text = row.get("text").getAsString();
      double start = row.get("start_ms").getAsDouble();
      double end = row.get("end_ms").getAsDouble();
      if (speaker.isBlank()
          || !Double.isFinite(start)
          || !Double.isFinite(end)
          || start != Math.rint(start)
          || end != Math.rint(end)
          || start < 0
          || end <= start
          || end > duration * 1000 + 1)
        throw new IllegalArgumentException("Invalid transcript interval or speaker");
      turns.add(new Turn(speaker, (long) start, (long) end, text));
    }
    turns.sort(Comparator.comparingLong(Turn::start).thenComparingLong(Turn::end));
    return turns;
  }

  static List<String> tokens(String text) {
    String normalized = ScoreAsr.normalize(text, false);
    return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split("\\s+"));
  }

  private static Map<String, List<String>> streams(List<Turn> turns) {
    var streams = new LinkedHashMap<String, List<String>>();
    for (var turn : turns)
      streams.computeIfAbsent(turn.speaker(), key -> new ArrayList<>()).addAll(tokens(turn.text()));
    return streams;
  }

  static int distance(List<String> reference, List<String> hypothesis) {
    if (reference.equals(hypothesis)) return 0;
    if (reference.size() < hypothesis.size()) return distance(hypothesis, reference);
    int[] previous = new int[hypothesis.size() + 1];
    int[] current = new int[previous.length];
    for (int column = 0; column < previous.length; column++) previous[column] = column;
    for (int row = 1; row <= reference.size(); row++) {
      current[0] = row;
      for (int column = 1; column < current.length; column++) {
        int substitution =
            previous[column - 1]
                + (reference.get(row - 1).equals(hypothesis.get(column - 1)) ? 0 : 1);
        current[column] =
            Math.min(substitution, Math.min(previous[column] + 1, current[column - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[hypothesis.size()];
  }

  static Assignment assign(int[][] costs) {
    int size = costs.length;
    if (size > 16) throw new IllegalArgumentException("At most 16 speakers are supported");
    int[] best = new int[1 << size];
    int[] chosen = new int[best.length];
    Arrays.fill(best, Integer.MAX_VALUE);
    best[0] = 0;
    for (int mask = 0; mask < best.length - 1; mask++) {
      int row = Integer.bitCount(mask);
      for (int column = 0; column < size; column++) {
        if ((mask & (1 << column)) != 0) continue;
        int next = mask | (1 << column);
        int value = best[mask] + costs[row][column];
        if (value < best[next]) {
          best[next] = value;
          chosen[next] = column;
        }
      }
    }
    int[] columns = new int[size];
    int mask = best.length - 1;
    for (int row = size - 1; row >= 0; row--) {
      columns[row] = chosen[mask];
      mask ^= 1 << columns[row];
    }
    return new Assignment(best[best.length - 1], columns);
  }

  static JsonObject score(JsonObject reference, JsonObject hypothesis, double duration) {
    var ref = read(reference, duration);
    var hyp = read(hypothesis, duration);
    var referenceStreams = streams(ref);
    var hypothesisStreams = streams(hyp);
    var referenceIds = new ArrayList<>(referenceStreams.keySet());
    var hypothesisIds = new ArrayList<>(hypothesisStreams.keySet());
    int size = Math.max(referenceIds.size(), hypothesisIds.size());
    if (size > 16) throw new IllegalArgumentException("At most 16 speakers are supported");
    int[][] costs = new int[size][size];
    for (int row = 0; row < size; row++) {
      List<String> expected =
          row < referenceIds.size() ? referenceStreams.get(referenceIds.get(row)) : List.of();
      for (int column = 0; column < size; column++) {
        List<String> actual =
            column < hypothesisIds.size()
                ? hypothesisStreams.get(hypothesisIds.get(column))
                : List.of();
        costs[row][column] = distance(expected, actual);
      }
    }
    Assignment assignment = assign(costs);
    List<String> refWords = tokens(String.join(" ", ref.stream().map(Turn::text).toList()));
    List<String> hypWords = tokens(String.join(" ", hyp.stream().map(Turn::text).toList()));
    if (refWords.isEmpty()) throw new IllegalArgumentException("Reference has no words");
    var errors = ScoreAsr.compare(refWords, hypWords);
    var result = new JsonObject();
    result.addProperty("reference_words", refWords.size());
    result.addProperty("lexical_word_errors", errors.total());
    result.addProperty("lexical_wer", errors.total() / (double) refWords.size());
    result.add("lexical_error_counts", JSON.toJsonTree(errors));
    result.addProperty("cp_word_errors", assignment.errors());
    result.addProperty("cpwer", assignment.errors() / (double) refWords.size());
    result.addProperty("reference_speakers", referenceIds.size());
    result.addProperty("hypothesis_speakers", hypothesisIds.size());
    result.addProperty("speaker_count_error", hypothesisIds.size() - referenceIds.size());
    var mapping = new JsonArray();
    for (int row = 0; row < size; row++) {
      int column = assignment.columns()[row];
      var pair = new JsonObject();
      if (row < referenceIds.size()) pair.addProperty("reference", referenceIds.get(row));
      if (column < hypothesisIds.size()) pair.addProperty("hypothesis", hypothesisIds.get(column));
      pair.addProperty("word_errors", costs[row][column]);
      mapping.add(pair);
    }
    result.add("cpwer_mapping", mapping);
    var diarization = ScoreDiarization.score(intervals(ref), intervals(hyp), duration);
    result.addProperty("der", diarization.der());
    result.add("diarization_seconds", JSON.toJsonTree(diarization));
    result.addProperty(
        "timing_policy",
        "utterance intervals; collar=0; overlap included; independent DER mapping");
    result.addProperty(
        "text_policy", "lexical ru-punctuation-v2-cardinal-integers; chronological start order");
    return result;
  }

  private static List<ScoreDiarization.Segment> intervals(List<Turn> turns) {
    return turns.stream()
        .map(
            turn ->
                new ScoreDiarization.Segment(
                    turn.start() / 1000.0, turn.end() / 1000.0, turn.speaker()))
        .toList();
  }

  private static JsonObject read(Path path) throws Exception {
    try (var reader = Files.newBufferedReader(path)) {
      return JSON.fromJson(reader, JsonObject.class);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 5 || args.length > 6)
      throw new IllegalArgumentException(
          "Usage: ScoreTranscripts manifest.json hypotheses_dir report.json split protocol"
              + " [--allow-provisional]");
    if (args.length == 6 && !args[5].equals("--allow-provisional"))
      throw new IllegalArgumentException("Unknown option: " + args[5]);
    Path manifestPath = Path.of(args[0]).toAbsolutePath();
    Path root = manifestPath.getParent();
    var manifest = read(manifestPath);
    boolean provisional =
        !"independently_reviewed".equals(manifest.get("reference_quality").getAsString());
    if (provisional && (args.length != 6 || !args[5].equals("--allow-provisional")))
      throw new IllegalArgumentException(
          "References are provisional; review them or explicitly pass --allow-provisional");
    var rows = new JsonArray();
    int failures = 0, words = 0, wordErrors = 0, cpErrors = 0;
    double referenceSeconds = 0, missed = 0, falseAlarm = 0, confusion = 0;
    for (var value : manifest.getAsJsonArray("samples")) {
      var sample = value.getAsJsonObject();
      if (!sample.get("split").getAsString().equals(args[3])
          || !sample.get("protocol").getAsString().equals(args[4])) continue;
      String id = sample.get("id").getAsString();
      var row = new JsonObject();
      row.addProperty("id", id);
      try {
        var metrics =
            score(
                read(root.resolve(sample.get("reference_json").getAsString())),
                read(Path.of(args[1]).resolve(id + ".json")),
                sample.get("seconds").getAsDouble());
        row.add("metrics", metrics);
        words += metrics.get("reference_words").getAsInt();
        wordErrors += metrics.get("lexical_word_errors").getAsInt();
        cpErrors += metrics.get("cp_word_errors").getAsInt();
        var diarization = metrics.getAsJsonObject("diarization_seconds");
        referenceSeconds += diarization.get("reference").getAsDouble();
        missed += diarization.get("missed").getAsDouble();
        falseAlarm += diarization.get("falseAlarm").getAsDouble();
        confusion += diarization.get("confusion").getAsDouble();
      } catch (Exception exception) {
        failures++;
        row.addProperty("error", exception.toString());
      }
      rows.add(row);
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("No samples match split and protocol");
    var report = new JsonObject();
    report.addProperty("reference_quality", manifest.get("reference_quality").getAsString());
    report.addProperty("provisional", provisional);
    report.addProperty("split", args[3]);
    report.addProperty("protocol", args[4]);
    report.addProperty("complete", failures == 0);
    report.addProperty("samples_count", rows.size());
    report.addProperty("failed_samples", failures);
    if (failures == 0) {
      report.addProperty("lexical_wer", wordErrors / (double) words);
      report.addProperty("cpwer", cpErrors / (double) words);
      report.addProperty("der", (missed + falseAlarm + confusion) / referenceSeconds);
    }
    report.add("samples", rows);
    Path output = Path.of(args[2]).toAbsolutePath();
    Files.createDirectories(output.getParent());
    Files.writeString(output, JSON.toJson(report) + "\n");
    System.out.println(
        "Wrote " + output + "; complete=" + (failures == 0) + "; provisional=" + provisional);
    if (failures != 0) throw new IllegalStateException("Failed samples: " + failures);
  }
}
