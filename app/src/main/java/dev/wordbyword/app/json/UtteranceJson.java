package dev.wordbyword.app.json;

import com.google.gson.JsonObject;
import dev.wordbyword.app.core.transcript.Transcript;
import java.util.Objects;
import java.util.Optional;

public final class UtteranceJson {
  private UtteranceJson() {}

  public static JsonObject toJson(Transcript.Utterance utterance, String speakerId) {
    validate(utterance.startMs(), utterance.endMs());
    var entry = new JsonObject();
    entry.addProperty("speaker_id", speakerId);
    entry.addProperty("start_ms", utterance.startMs());
    entry.addProperty("end_ms", utterance.endMs());
    entry.addProperty("text", Objects.requireNonNull(utterance.text(), "text"));
    return entry;
  }

  public static Transcript.Utterance fromJson(JsonObject entry) {
    Optional<String> speakerId =
        entry.has("speaker_id") && !entry.get("speaker_id").isJsonNull()
            ? Optional.of(string(entry, "speaker_id"))
            : Optional.empty();
    long start = number(entry, "start_ms");
    long end = number(entry, "end_ms");
    validate(start, end);
    return new Transcript.Utterance(speakerId, string(entry, "text"), start, end);
  }

  private static long number(JsonObject entry, String key) {
    var value = entry.getAsJsonPrimitive(key);
    if (!value.isNumber()) throw new IllegalArgumentException("Expected numeric " + key);
    return value.getAsBigDecimal().longValueExact();
  }

  private static String string(JsonObject entry, String key) {
    var value = entry.getAsJsonPrimitive(key);
    if (!value.isString()) throw new IllegalArgumentException("Expected string " + key);
    return value.getAsString();
  }

  private static void validate(long start, long end) {
    if (start < 0 || end < start)
      throw new IllegalArgumentException("Некорректные временные метки реплики");
  }
}
