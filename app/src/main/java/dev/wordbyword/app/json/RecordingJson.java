package dev.wordbyword.app.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.OptionalLong;

public final class RecordingJson {
  public record Entry(Recording recording, Transcript transcript) {}

  private static final Gson JSON =
      new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();

  private RecordingJson() {}

  public static void write(Writer writer, Recording recording, Transcript transcript)
      throws IOException {
    try {
      JsonObject document = encode(recording, transcript);
      decode(document);
      JSON.toJson(document, writer);
    } catch (RuntimeException exception) {
      throw new IOException("Cannot encode recording metadata", exception);
    }
  }

  private static JsonObject encode(Recording recording, Transcript transcript) {
    var document = new JsonObject();
    document.addProperty("version", 2);
    document.addProperty("id", recording.id());
    document.addProperty("audio_uri", recording.audioUri().toString());
    document.addProperty("title", recording.title());
    if (recording.durationMs().isPresent())
      document.addProperty("duration_ms", recording.durationMs().getAsLong());
    if (transcript != null) {
      var speakers = new JsonArray();
      for (var speaker : transcript.speakers()) {
        var entry = new JsonObject();
        entry.addProperty("id", speaker.id());
        entry.addProperty("name", speaker.displayName());
        speakers.add(entry);
      }
      document.add("speakers", speakers);
      var utterances = new JsonArray();
      for (var utterance : transcript.utterances()) {
        utterances.add(UtteranceJson.toJson(utterance, utterance.speakerId().orElse(null)));
      }
      document.add("transcript", utterances);
    }
    return document;
  }

  public static Entry read(Reader reader) throws IOException {
    try {
      return decode(JSON.fromJson(reader, JsonObject.class));
    } catch (RuntimeException exception) {
      throw new IOException("Invalid recording metadata", exception);
    }
  }

  private static long number(JsonObject object, String key) {
    var value = object.getAsJsonPrimitive(key);
    if (!value.isNumber()) throw new IllegalArgumentException("Expected numeric " + key);
    return value.getAsBigDecimal().longValueExact();
  }

  private static Entry decode(JsonObject document) throws IOException {
    try {
      long version = number(document, "version");
      if (version != 1 && version != 2)
        throw new IllegalArgumentException("Unsupported storage version");
      String id = document.get("id").getAsString();
      String title = document.get("title").getAsString();
      URI uri = URI.create(document.get("audio_uri").getAsString());
      OptionalLong duration =
          document.has("duration_ms")
              ? OptionalLong.of(number(document, "duration_ms"))
              : OptionalLong.empty();
      if (id.isBlank() || !uri.isAbsolute() || (duration.isPresent() && duration.getAsLong() < 0))
        throw new IllegalArgumentException("Invalid recording");
      var recording = new Recording(id, uri, title, duration);
      String transcriptKey = version == 1 ? "utterances" : "transcript";
      if (!document.has(transcriptKey) && !document.has("speakers"))
        return new Entry(recording, null);
      var speakers = new ArrayList<Transcript.Speaker>();
      var ids = new HashSet<String>();
      for (var element : document.getAsJsonArray("speakers")) {
        var speaker = element.getAsJsonObject();
        String speakerId = speaker.get("id").getAsString();
        String name = speaker.get("name").getAsString();
        if (speakerId.isBlank() || name.isBlank() || !ids.add(speakerId))
          throw new IllegalArgumentException("Invalid speaker");
        speakers.add(new Transcript.Speaker(speakerId, name));
      }
      var utterances = new ArrayList<Transcript.Utterance>();
      for (var element : document.getAsJsonArray(transcriptKey)) {
        var utterance = UtteranceJson.fromJson(element.getAsJsonObject());
        if ((duration.isPresent() && utterance.endMs() > duration.getAsLong())
            || (utterance.speakerId().isPresent() && !ids.contains(utterance.speakerId().get())))
          throw new IllegalArgumentException("Invalid utterance");
        utterances.add(utterance);
      }
      return new Entry(recording, new Transcript(recording, speakers, utterances));
    } catch (RuntimeException exception) {
      throw new IOException("Invalid recording metadata", exception);
    }
  }
}
