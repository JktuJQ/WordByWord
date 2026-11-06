package dev.wordbyword.app.export;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.Test;

public class TranscriptJsonExporterTest {
  private static Recording recording() {
    return new Recording(
        "sample", URI.create("file:///sample.wav"), "Запись", OptionalLong.empty());
  }

  @Test
  public void exportsExactRequiredSchemaAndEscapesText() throws Exception {
    var transcript =
        new Transcript(
            recording(),
            List.of(new Transcript.Speaker("a", "Имя")),
            List.of(
                new Transcript.Utterance(
                    Optional.of("a"), "Да: \"текст\"\n\\\t\u0001 😀", 1234, 5678)));
    var writer = new StringWriter();
    TranscriptJsonExporter.write(transcript, writer);
    var root = JsonParser.parseString(writer.toString()).getAsJsonObject();
    assertEquals(Set.of("transcript"), root.keySet());
    var utterances = root.getAsJsonArray("transcript");
    assertEquals(1, utterances.size());
    var utterance = utterances.get(0).getAsJsonObject();
    assertEquals(Set.of("speaker_id", "start_ms", "end_ms", "text"), utterance.keySet());
    assertEquals("a", utterance.get("speaker_id").getAsString());
    assertEquals(1234L, utterance.get("start_ms").getAsLong());
    assertEquals(5678L, utterance.get("end_ms").getAsLong());
    assertEquals(transcript.utterances().get(0).text(), utterance.get("text").getAsString());
    assertFalse(writer.toString().contains("Имя"));
  }

  @Test
  public void unknownSpeakerDoesNotCollideWithKnownIds() throws Exception {
    var transcript =
        new Transcript(
            recording(),
            List.of(new Transcript.Speaker("unknown", "Известный")),
            List.of(new Transcript.Utterance(Optional.empty(), "Речь", 0, 1)));
    var writer = new StringWriter();
    TranscriptJsonExporter.write(transcript, writer);
    assertTrue(writer.toString().contains("\"speaker_id\":\"_unknown\""));
  }

  @Test
  public void exportsSilenceAsEmptyArray() throws Exception {
    var writer = new StringWriter();
    TranscriptJsonExporter.write(new Transcript(recording(), List.of(), List.of()), writer);
    assertEquals("{\"transcript\":[]}\n", writer.toString());
  }

  @Test
  public void rejectsInvalidTimingsBeforeWriting() throws Exception {
    var transcript =
        new Transcript(
            recording(),
            List.of(),
            List.of(new Transcript.Utterance(Optional.empty(), "Речь", 10, 2)));
    var writer = new StringWriter();
    assertThrows(
        IllegalArgumentException.class, () -> TranscriptJsonExporter.write(transcript, writer));
    assertEquals("", writer.toString());
  }
}
