package dev.wordbyword.app.export;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.demo.DemoBackend;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class TranscriptJsonExporterTest {
  @Test
  public void exportsExactRequiredSchemaAndEscapesText() throws Exception {
    var transcript =
        new Transcript(
            DemoBackend.recording(),
            List.of(new Transcript.Speaker("a", "Имя")),
            List.of(
                new Transcript.Utterance(
                    Optional.of("a"), "Да: \"текст\"\n\\\t\u0001 😀", 1234, 5678)));
    var writer = new StringWriter();
    TranscriptJsonExporter.write(transcript, writer);
    assertEquals(
        "{\"transcript\":[{\"speaker_id\":\"a\",\"start_ms\":1234,\"end_ms\":5678,\"text\":\"Да:"
            + " \\\"текст\\\"\\n"
            + "\\\\\\t\\u0001 \\ud83d\\ude00\"}]}\n",
        writer.toString());
    assertFalse(writer.toString().contains("Имя"));
  }

  @Test
  public void unknownSpeakerDoesNotCollideWithKnownIds() throws Exception {
    var transcript =
        new Transcript(
            DemoBackend.recording(),
            List.of(new Transcript.Speaker("unknown", "Известный")),
            List.of(new Transcript.Utterance(Optional.empty(), "Речь", 0, 1)));
    var writer = new StringWriter();
    TranscriptJsonExporter.write(transcript, writer);
    assertTrue(writer.toString().contains("\"speaker_id\":\"_unknown\""));
  }

  @Test
  public void exportsSilenceAsEmptyArray() throws Exception {
    var writer = new StringWriter();
    TranscriptJsonExporter.write(
        new Transcript(DemoBackend.recording(), List.of(), List.of()), writer);
    assertEquals("{\"transcript\":[]}\n", writer.toString());
  }

  @Test
  public void rejectsInvalidTimingsBeforeWriting() throws Exception {
    var transcript =
        new Transcript(
            DemoBackend.recording(),
            List.of(),
            List.of(new Transcript.Utterance(Optional.empty(), "Речь", 10, 2)));
    var writer = new StringWriter();
    assertThrows(
        IllegalArgumentException.class, () -> TranscriptJsonExporter.write(transcript, writer));
    assertEquals("", writer.toString());
  }
}
