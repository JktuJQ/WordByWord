package dev.wordbyword.app.export;

import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;

public final class TranscriptJsonExporter {
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
    writer.write("{\"transcript\":[");
    boolean first = true;
    for (var utterance : transcript.utterances()) {
      if (!first) {
        writer.write(',');
      }
      first = false;
      writer.write("{\"speaker_id\":");
      quote(utterance.speakerId().orElse(unknown), writer);
      writer.write(
          ",\"start_ms\":"
              + utterance.startMs()
              + ",\"end_ms\":"
              + utterance.endMs()
              + ",\"text\":");
      quote(utterance.text(), writer);
      writer.write('}');
    }
    writer.write("]}\n");
  }

  private static void quote(String value, Writer writer) throws IOException {
    writer.write('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> writer.write("\\\"");
        case '\\' -> writer.write("\\\\");
        case '\n' -> writer.write("\\n");
        case '\r' -> writer.write("\\r");
        case '\t' -> writer.write("\\t");
        default -> {
          if (character < 0x20 || Character.isSurrogate(character)) {
            writer.write(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
          } else {
            writer.write(character);
          }
        }
      }
    }
    writer.write('"');
  }
}
