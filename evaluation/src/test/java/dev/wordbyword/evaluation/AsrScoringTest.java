package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.Test;

public class AsrScoringTest {
  @Test
  public void countsSubstitutionDeletionAndInsertion() {
    assertEquals(new ScoreAsr.Errors(1, 0, 0), ScoreAsr.compare(List.of("a"), List.of("b")));
    assertEquals(new ScoreAsr.Errors(0, 1, 0), ScoreAsr.compare(List.of("a"), List.of()));
    assertEquals(new ScoreAsr.Errors(0, 0, 1), ScoreAsr.compare(List.of(), List.of("a")));
    assertEquals(
        3,
        ScoreAsr.compare(
                List.of("k", "i", "t", "t", "e", "n"), List.of("s", "i", "t", "t", "i", "n", "g"))
            .total());
  }

  @Test
  public void normalizesBothSides() {
    assertEquals("елка , привет !", ScoreAsr.normalize(" Ёлка, ПРИВЕТ! "));
  }

  @Test
  public void punctuationErrorsRemainVisible() {
    var manifest = object("{\"samples\":[{\"file\":\"a.wav\",\"reference\":\"Привет, мир!\"}]}");
    var predictions = object("{\"runs\":[{\"file\":\"a.wav\",\"hypothesis\":\"привет мир\"}]}");
    var report = ScoreAsr.score(manifest, predictions);
    assertEquals(0.5, report.get("wer").getAsDouble(), 0.0);
    assertTrue(report.get("cer").getAsDouble() > 0);
    assertEquals(0.0, report.get("lexical_wer").getAsDouble(), 0.0);
    assertEquals(0, report.get("exact_utterances").getAsInt());
    predictions
        .getAsJsonArray("runs")
        .get(0)
        .getAsJsonObject()
        .addProperty("hypothesis", "привет , мир !");
    assertEquals(0.0, ScoreAsr.score(manifest, predictions).get("wer").getAsDouble(), 0.0);
  }

  @Test
  public void usesSourceRawTranscriptForPunctuationAndKeepsLexicalReference() {
    var manifest =
        object(
            "{\"samples\":[{\"file\":\"a.wav\",\"reference\":\"привет"
                + " мир\",\"reference_raw\":\"Привет, мир!\"}]}");
    var predictions = object("{\"runs\":[{\"file\":\"a.wav\",\"hypothesis\":\"Привет, мир!\"}]}");
    var report = ScoreAsr.score(manifest, predictions);
    assertEquals(0.0, report.get("wer").getAsDouble(), 0.0);
    assertEquals(0.0, report.get("lexical_wer").getAsDouble(), 0.0);
    assertEquals(
        "Привет, мир!",
        report.getAsJsonArray("samples").get(0).getAsJsonObject().get("reference").getAsString());
  }

  @Test
  public void ignoresCardinalNumberSpellingWithoutIgnoringTheirValue() {
    assertEquals(
        ScoreAsr.normalize("В 2026 году 24 участника."),
        ScoreAsr.normalize("В две тысячи двадцать шесть году двадцать четыре участника."));
    assertEquals(
        ScoreAsr.normalize("2 тысячи и 1 миллион"),
        ScoreAsr.normalize("две тысячи и один миллион"));
    assertNotEquals(ScoreAsr.normalize("24 участника"), ScoreAsr.normalize("25 участников"));
    assertEquals("одиннадцатый", ScoreAsr.normalize("Одиннадцатый"));
    assertEquals("00123", ScoreAsr.normalize("00123"));
    assertEquals("0", ScoreAsr.normalize("ноль тысяч"));
    assertEquals("20 30", ScoreAsr.normalize("двадцать тридцать"));
    assertEquals("1 1", ScoreAsr.normalize("один один"));
    assertEquals("тысячи лет", ScoreAsr.normalize("тысячи лет"));
    assertEquals("1000 лет", ScoreAsr.normalize("тысячу лет"));
    assertEquals(
        ScoreAsr.normalize("4 892 метра"),
        ScoreAsr.normalize("четыре тысячи восемьсот девяносто два метра"));
    assertEquals("1234567", ScoreAsr.normalize("1\u00a0234\u00a0567"));
  }

  @Test
  public void evaluatesOnlyTheRequestedPartitionAndRequiresAllItsSamples() {
    var manifest =
        object(
            """
            {"samples":[
              {"file":"a.wav","reference":"привет","partition":"development"},
              {"file":"b.wav","reference":"добрый день","partition":"evaluation"}]}
            """);
    var predictions =
        object(
            """
            {"runs":[
              {"file":"a.wav","hypothesis":"привет","status":"READY"},
              {"file":"b.wav","hypothesis":"добрый","status":"READY"}]}
            """);
    assertEquals(
        0.0,
        ScoreAsr.scorePartition(manifest, predictions, "development").get("wer").getAsDouble(),
        0.0);
    assertEquals(
        0.5,
        ScoreAsr.scorePartition(manifest, predictions, "evaluation").get("wer").getAsDouble(),
        0.0);
    predictions.getAsJsonArray("runs").remove(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreAsr.scorePartition(manifest, predictions, "evaluation"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreAsr.scorePartition(manifest, predictions, "unknown"));
  }

  @Test
  public void rejectsDuplicateAndFailedRuns() {
    var manifest = object("{\"samples\":[{\"file\":\"a.wav\",\"reference\":\"привет\"}]}");
    var predictions =
        object("{\"runs\":[{\"file\":\"a.wav\",\"hypothesis\":\"привет\",\"status\":\"ERROR\"}]}");
    assertThrows(IllegalArgumentException.class, () -> ScoreAsr.score(manifest, predictions));
    predictions.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("status", "READY");
    predictions.getAsJsonArray("runs").add(predictions.getAsJsonArray("runs").get(0).deepCopy());
    assertThrows(IllegalArgumentException.class, () -> ScoreAsr.score(manifest, predictions));
  }

  private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
