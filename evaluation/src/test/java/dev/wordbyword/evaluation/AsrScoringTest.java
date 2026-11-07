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
    assertEquals("елка привет", ScoreAsr.normalize(" Ёлка, ПРИВЕТ! "));
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
