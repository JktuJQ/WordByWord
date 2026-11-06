package dev.wordbyword.app.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalLong;

final class RecordingJson {
  record Entry(Recording recording, Transcript transcript) {}

  private static final Gson JSON = new Gson();

  private RecordingJson() {}

  static void write(Path target, Recording recording, Transcript transcript) throws IOException {
    var document = new JsonObject();
    document.addProperty("version", 1);
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
        var entry = new JsonObject();
        utterance.speakerId().ifPresent(id -> entry.addProperty("speaker_id", id));
        entry.addProperty("text", utterance.text());
        entry.addProperty("start_ms", utterance.startMs());
        entry.addProperty("end_ms", utterance.endMs());
        utterances.add(entry);
      }
      document.add("utterances", utterances);
    }
    decode(document);
    Path temporary = Files.createTempFile(target.getParent(), ".metadata-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        JSON.toJson(document, writer);
      }
      try (var channel =
          java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static Entry read(Path path) throws IOException {
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        var json = new com.google.gson.stream.JsonReader(reader)) {
      json.setStrictness(com.google.gson.Strictness.STRICT);
      var document = JsonParser.parseReader(json).getAsJsonObject();
      if (json.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
        throw new IOException("Trailing recording metadata");
      return decode(document);
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
      if (number(document, "version") != 1)
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
      if (!document.has("utterances") && !document.has("speakers"))
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
      for (var element : document.getAsJsonArray("utterances")) {
        var utterance = element.getAsJsonObject();
        Optional<String> speakerId =
            utterance.has("speaker_id")
                ? Optional.of(utterance.get("speaker_id").getAsString())
                : Optional.empty();
        long start = number(utterance, "start_ms");
        long end = number(utterance, "end_ms");
        if (start < 0
            || end < start
            || (duration.isPresent() && end > duration.getAsLong())
            || (speakerId.isPresent() && !ids.contains(speakerId.get())))
          throw new IllegalArgumentException("Invalid utterance");
        utterances.add(
            new Transcript.Utterance(speakerId, utterance.get("text").getAsString(), start, end));
      }
      return new Entry(recording, new Transcript(recording, speakers, utterances));
    } catch (RuntimeException exception) {
      throw new IOException("Invalid recording metadata", exception);
    }
  }
}
