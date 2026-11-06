package dev.wordbyword.app.export;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.json.UtteranceJson;
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
    var utterances = new JsonArray();
    for (var utterance : transcript.utterances()) {
      utterances.add(UtteranceJson.toJson(utterance, utterance.speakerId().orElse(unknown)));
    }
    var document = new JsonObject();
    document.add("transcript", utterances);
    JSON.toJson(document, writer);
    writer.write('\n');
  }
}
