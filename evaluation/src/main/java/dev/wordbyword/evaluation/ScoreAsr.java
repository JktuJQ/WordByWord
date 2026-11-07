package dev.wordbyword.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class ScoreAsr {
  private static final Gson JSON = new Gson();

  public record Errors(int substitutions, int deletions, int insertions) {
    public int total() {
      return substitutions + deletions + insertions;
    }

    Errors substitute() {
      return new Errors(substitutions + 1, deletions, insertions);
    }

    Errors delete() {
      return new Errors(substitutions, deletions + 1, insertions);
    }

    Errors insert() {
      return new Errors(substitutions, deletions, insertions + 1);
    }
  }

  public static String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replace('ё', 'е')
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim();
  }

  public static Errors compare(List<String> reference, List<String> hypothesis) {
    Errors[] previous = new Errors[hypothesis.size() + 1];
    for (int j = 0; j < previous.length; j++) previous[j] = new Errors(0, 0, j);
    for (int i = 1; i <= reference.size(); i++) {
      Errors[] current = new Errors[previous.length];
      current[0] = new Errors(0, i, 0);
      for (int j = 1; j < current.length; j++) {
        if (reference.get(i - 1).equals(hypothesis.get(j - 1))) current[j] = previous[j - 1];
        else {
          Errors best = previous[j - 1].substitute();
          Errors insertion = current[j - 1].insert();
          Errors deletion = previous[j].delete();
          if (insertion.total() < best.total()) best = insertion;
          if (deletion.total() < best.total()) best = deletion;
          current[j] = best;
        }
      }
      previous = current;
    }
    return previous[hypothesis.size()];
  }

  private static List<String> words(String text) {
    return text.isEmpty() ? List.of() : Arrays.asList(text.split(" "));
  }

  private static List<String> characters(String text) {
    return text.replace(" ", "")
        .codePoints()
        .mapToObj(c -> new String(Character.toChars(c)))
        .toList();
  }

  public static JsonObject score(JsonObject manifest, JsonObject hypotheses) {
    var byFile = new HashMap<String, String>();
    String key = hypotheses.has("runs") ? "runs" : "samples";
    for (var entry : hypotheses.getAsJsonArray(key)) {
      var row = entry.getAsJsonObject();
      String file = row.get("file").getAsString();
      if (row.has("status") && !row.get("status").getAsString().equals("READY")) {
        throw new IllegalArgumentException("Unsuccessful run: " + file);
      }
      String text;
      if (row.has("hypothesis")) text = row.get("hypothesis").getAsString();
      else {
        var parts = new ArrayList<String>();
        for (var segment : row.getAsJsonArray("segments"))
          parts.add(segment.getAsJsonObject().get("text").getAsString());
        text = String.join(" ", parts);
      }
      if (byFile.put(file, text) != null)
        throw new IllegalArgumentException("Duplicate run: " + file);
    }
    int wordCount = 0, charCount = 0, charErrors = 0, exact = 0;
    Errors errors = new Errors(0, 0, 0);
    var samples = new JsonArray();
    for (var entry : manifest.getAsJsonArray("samples")) {
      var sample = entry.getAsJsonObject();
      String file = sample.get("file").getAsString();
      if (!byFile.containsKey(file)) throw new IllegalArgumentException("Missing run: " + file);
      String reference = normalize(sample.get("reference").getAsString());
      String hypothesis = normalize(byFile.get(file));
      Errors row = compare(words(reference), words(hypothesis));
      errors =
          new Errors(
              errors.substitutions() + row.substitutions(),
              errors.deletions() + row.deletions(),
              errors.insertions() + row.insertions());
      wordCount += words(reference).size();
      charCount += characters(reference).size();
      charErrors += compare(characters(reference), characters(hypothesis)).total();
      if (reference.equals(hypothesis)) exact++;
      var output = new JsonObject();
      output.addProperty("file", file);
      output.addProperty("reference", sample.get("reference").getAsString());
      output.addProperty("hypothesis", byFile.get(file));
      output.add("errors", JSON.toJsonTree(row));
      samples.add(output);
    }
    if (byFile.size() != samples.size() || wordCount == 0 || charCount == 0)
      throw new IllegalArgumentException("Invalid sample set");
    var result = new JsonObject();
    result.addProperty("samples_count", samples.size());
    result.addProperty("words", wordCount);
    result.addProperty("word_errors", errors.total());
    result.add("word_error_counts", JSON.toJsonTree(errors));
    result.addProperty("wer", errors.total() / (double) wordCount);
    result.addProperty("characters", charCount);
    result.addProperty("character_errors", charErrors);
    result.addProperty("cer", charErrors / (double) charCount);
    result.addProperty("exact_utterances", exact);
    result.add("samples", samples);
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 && args.length != 4)
      throw new IllegalArgumentException(
          "Usage: ScoreAsr manifest.json hypotheses.json output.json [partition]");
    var manifest =
        JSON.fromJson(
            new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8),
            JsonObject.class);
    var hypotheses =
        JSON.fromJson(
            new String(Files.readAllBytes(Path.of(args[1])), StandardCharsets.UTF_8),
            JsonObject.class);
    var result =
        args.length == 4
            ? scorePartition(manifest, hypotheses, args[3])
            : score(manifest, hypotheses);
    Files.write(
        Path.of(args[2]),
        (new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    System.out.printf(
        Locale.ROOT,
        "WER %.2f%%; CER %.2f%%; exact %s/%s%n",
        result.get("wer").getAsDouble() * 100,
        result.get("cer").getAsDouble() * 100,
        result.get("exact_utterances"),
        result.get("samples_count"));
  }

  public static JsonObject scorePartition(
      JsonObject manifest, JsonObject hypotheses, String partition) {
    var selected = new JsonArray();
    var files = new java.util.HashSet<String>();
    var known = new java.util.HashSet<String>();
    for (var element : manifest.getAsJsonArray("samples")) {
      var sample = element.getAsJsonObject();
      String file = sample.get("file").getAsString();
      if (!known.add(file)) throw new IllegalArgumentException("Duplicate sample: " + file);
      if (sample.has("partition") && sample.get("partition").getAsString().equals(partition)) {
        selected.add(sample);
        files.add(file);
      }
    }
    if (selected.isEmpty())
      throw new IllegalArgumentException("Unknown or empty partition: " + partition);
    var runs = new JsonArray();
    var seen = new java.util.HashSet<String>();
    String key = hypotheses.has("runs") ? "runs" : "samples";
    for (var element : hypotheses.getAsJsonArray(key)) {
      String file = element.getAsJsonObject().get("file").getAsString();
      if (!known.contains(file) || !seen.add(file))
        throw new IllegalArgumentException("Unknown or duplicate run: " + file);
      if (files.contains(file)) runs.add(element);
    }
    var subset = new JsonObject();
    subset.add("samples", selected);
    var predictions = new JsonObject();
    predictions.add(key, runs);
    var result = score(subset, predictions);
    result.addProperty("partition", partition);
    return result;
  }
}
