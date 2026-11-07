package dev.wordbyword.app.export;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;

public final class TranscriptJsonExporter {
  private static final Gson JSON = new Gson();

  private TranscriptJsonExporter() {}

  public static void write(Transcript transcript, Writer writer) throws IOException {
    var usedIds = new HashSet<String>();
    transcript.speakers().forEach(speaker -> usedIds.add(speaker.id()));
    transcript.utterances().forEach(utterance -> utterance.speakerId().ifPresent(usedIds::add));
    String unknown = "unknown";
    while (usedIds.contains(unknown)) {
      unknown = "_" + unknown;
    }
    for (var utterance : transcript.utterances()) {
      if (utterance.startMs() < 0 || utterance.endMs() < utterance.startMs()) {
        throw new IllegalArgumentException("Некорректные временные метки реплики");
      }
    }
    var utterances = new JsonArray();
    for (var utterance : transcript.utterances()) {
      var entry = new JsonObject();
      entry.addProperty("speaker_id", utterance.speakerId().orElse(unknown));
      entry.addProperty("start_ms", utterance.startMs());
      entry.addProperty("end_ms", utterance.endMs());
      entry.addProperty("text", utterance.text());
      utterances.add(entry);
    }
    var document = new JsonObject();
    document.add("transcript", utterances);
    JSON.toJson(document, writer);
    writer.write('\n');
  }
}
