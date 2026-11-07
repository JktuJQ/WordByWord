package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScoreTranscriptsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static JsonObject transcript(Object[]... rows) {
    var result = new JsonObject();
    var array = new JsonArray();
    for (var values : rows) {
      var row = new JsonObject();
      row.addProperty("speaker_id", (String) values[0]);
      row.addProperty("start_ms", (Number) values[1]);
      row.addProperty("end_ms", (Number) values[2]);
      row.addProperty("text", (String) values[3]);
      array.add(row);
    }
    result.add("transcript", array);
    return result;
  }

  private static Object[] turn(String id, double start, double end, String text) {
    return new Object[] {id, start, end, text};
  }

  private static JsonObject reference() {
    return transcript(turn("a", 0, 5000, "добрый день"), turn("b", 5000, 10000, "привет всем"));
  }

  @Test
  public void globalPermutationDoesNotChangeScores() {
    var hypothesis =
        transcript(turn("y", 0, 5000, "Добрый день!"), turn("x", 5000, 10000, "Привет всем."));
    var result = ScoreTranscripts.score(reference(), hypothesis, 10);
    assertEquals(0, result.get("cpwer").getAsDouble(), 0);
    assertEquals(0, result.get("der").getAsDouble(), 0);
  }

  @Test
  public void collapsedSpeakersHavePerfectWerButBadCpwer() {
    var hypothesis = transcript(turn("x", 0, 10000, "добрый день привет всем"));
    var result = ScoreTranscripts.score(reference(), hypothesis, 10);
    assertEquals(0, result.get("lexical_wer").getAsDouble(), 0);
    assertEquals(1, result.get("cpwer").getAsDouble(), 0);
    assertEquals(0.5, result.get("der").getAsDouble(), 0);
    assertEquals(-1, result.get("speaker_count_error").getAsInt());
  }

  @Test
  public void extraSpeakerAndWordsArePenalized() {
    var hypothesis =
        transcript(
            turn("x", 0, 5000, "добрый день"),
            turn("y", 5000, 10000, "привет всем"),
            turn("z", 10000, 11000, "лишние слова"));
    var result = ScoreTranscripts.score(reference(), hypothesis, 12);
    assertEquals(0.5, result.get("cpwer").getAsDouble(), 0);
    assertEquals(1, result.get("speaker_count_error").getAsInt());
    assertEquals(0.1, result.get("der").getAsDouble(), 1e-8);
  }

  @Test
  public void missingEverythingIsNotSkipped() {
    var result = ScoreTranscripts.score(reference(), transcript(), 10);
    assertEquals(1, result.get("cpwer").getAsDouble(), 0);
    assertEquals(1, result.get("lexical_wer").getAsDouble(), 0);
    assertEquals(1, result.get("der").getAsDouble(), 0);
  }

  @Test
  public void speakerSwitchWithinRecordingCannotBeFixedByOnePermutation() {
    var ref =
        transcript(
            turn("a", 0, 1000, "раз"),
            turn("b", 1000, 2000, "два"),
            turn("a", 2000, 3000, "три"),
            turn("b", 3000, 4000, "четыре"));
    var hypothesis =
        transcript(
            turn("x", 0, 1000, "раз"),
            turn("y", 1000, 2000, "два"),
            turn("y", 2000, 3000, "три"),
            turn("x", 3000, 4000, "четыре"));
    var result = ScoreTranscripts.score(ref, hypothesis, 4);
    assertEquals(0, result.get("lexical_wer").getAsDouble(), 0);
    assertTrue(result.get("cpwer").getAsDouble() > 0);
    assertEquals(0.5, result.get("der").getAsDouble(), 0);
  }

  @Test
  public void timingErrorsRemainVisibleWhenWordsAndSpeakersMatch() {
    var hypothesis =
        transcript(turn("x", 1000, 4000, "добрый день"), turn("y", 6000, 9000, "привет всем"));
    var result = ScoreTranscripts.score(reference(), hypothesis, 10);
    assertEquals(0, result.get("cpwer").getAsDouble(), 0);
    assertEquals(0.4, result.get("der").getAsDouble(), 1e-8);
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativeDurationCannotProduceQualityNumbers() {
    ScoreTranscripts.score(reference(), transcript(turn("x", 6000, 5000, "ошибка")), 10);
  }

  @Test(expected = IllegalArgumentException.class)
  public void fractionalMillisecondsAreRejected() {
    ScoreTranscripts.score(reference(), transcript(turn("x", 0.5, 5000, "ошибка")), 10);
  }

  @Test
  public void integerDistanceMatchesErrorCountScorer() {
    var random = new Random(47);
    for (int test = 0; test < 100; test++) {
      List<String> ref = random.ints(random.nextInt(15), 0, 4).mapToObj(Integer::toString).toList();
      List<String> hyp = random.ints(random.nextInt(15), 0, 4).mapToObj(Integer::toString).toList();
      assertEquals(ScoreAsr.compare(ref, hyp).total(), ScoreTranscripts.distance(ref, hyp));
    }
  }

  @Test
  public void missingRunsWriteFailureReportWithoutHeadlineMetrics() throws Exception {
    var directory = temporary.getRoot().toPath();
    Files.writeString(directory.resolve("ref.json"), reference().toString());
    var manifest = new JsonObject();
    manifest.addProperty("reference_quality", "unreviewed");
    var samples = new JsonArray();
    var sample = new JsonObject();
    sample.addProperty("id", "missing");
    sample.addProperty("reference_json", "ref.json");
    sample.addProperty("split", "evaluation");
    sample.addProperty("protocol", "short");
    sample.addProperty("seconds", 10);
    samples.add(sample);
    manifest.add("samples", samples);
    var path = directory.resolve("manifest.json");
    var output = directory.resolve("report.json");
    Files.writeString(path, manifest.toString());
    String[] arguments = {
      path.toString(),
      directory.toString(),
      output.toString(),
      "evaluation",
      "short",
      "--allow-provisional"
    };
    assertThrows(
        IllegalArgumentException.class, () -> ScoreTranscripts.main(Arrays.copyOf(arguments, 5)));
    assertFalse(Files.exists(output));
    assertThrows(IllegalStateException.class, () -> ScoreTranscripts.main(arguments));
    var report = new Gson().fromJson(Files.readString(output), JsonObject.class);
    assertFalse(report.get("complete").getAsBoolean());
    assertTrue(report.get("provisional").getAsBoolean());
    assertFalse(report.has("cpwer"));
    assertFalse(report.has("lexical_wer"));
    assertFalse(report.has("der"));
  }

  @Test
  public void assignmentFindsGlobalRatherThanGreedyMinimum() {
    assertEquals(3, ScoreTranscripts.assign(new int[][] {{1, 2}, {1, 100}}).errors());
  }
}
