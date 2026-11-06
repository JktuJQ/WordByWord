package dev.wordbyword.app.core.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.wordbyword.app.core.recording.Recording;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.Test;

public class TranscriptTest {
  private Recording recording(String id) {
    return new Recording(
        id, URI.create("content://example/audio/" + id), "Встреча", OptionalLong.empty());
  }

  @Test
  public void producerCollectionsCannotChangeTranscriptSnapshot() {
    var speakers = new ArrayList<>(List.of(new Transcript.Speaker("a", "Анна")));
    var utterances =
        new ArrayList<>(List.of(new Transcript.Utterance(Optional.of("a"), "Привет", 1000, 2000)));
    var transcript = new Transcript(recording("1"), speakers, utterances);

    speakers.clear();
    utterances.clear();

    assertEquals("Анна", transcript.speakers().get(0).displayName());
    assertEquals("Привет", transcript.utterances().get(0).text());
    assertThrows(UnsupportedOperationException.class, () -> transcript.utterances().clear());
  }

  @Test
  public void unknownSpeakerAndUnknownDurationAreRepresentable() {
    var utterance = new Transcript.Utterance(Optional.empty(), "Неясная реплика", 0, 500);
    var transcript = new Transcript(recording("1"), List.of(), List.of(utterance));

    assertTrue(transcript.utterances().get(0).speakerId().isEmpty());
    assertTrue(transcript.recording().durationMs().isEmpty());
  }

  @Test
  public void displayNamesCanChangeWithoutChangingUtteranceIdentityOrAnotherRecording() {
    var utterance = new Transcript.Utterance(Optional.of("a"), "Текст", 0, 1000);
    var first =
        new Transcript(
            recording("1"), List.of(new Transcript.Speaker("a", "Анна")), List.of(utterance));
    var second =
        new Transcript(
            recording("2"), List.of(new Transcript.Speaker("a", "Борис")), List.of(utterance));
    var renamed = new TranscriptEditor(first).renameSpeaker("a", "  Анна Петрова  ").build();
    assertThrows(
        IllegalArgumentException.class, () -> new TranscriptEditor(first).renameSpeaker("a", " "));
    assertThrows(
        NoSuchElementException.class,
        () -> new TranscriptEditor(first).renameSpeaker("missing", "Имя"));

    assertEquals(first.utterances(), renamed.utterances());
    assertEquals("Анна", first.speakers().get(0).displayName());
    assertEquals("Борис", second.speakers().get(0).displayName());
    assertEquals("Анна Петрова", renamed.speakers().get(0).displayName());
  }

  @Test
  public void editorBatchesChangesAndBuildsIndependentSnapshots() {
    var original =
        new Transcript(
            recording("1"),
            List.of(new Transcript.Speaker("a", "Первый"), new Transcript.Speaker("b", "Второй")),
            List.of());
    var editor = new TranscriptEditor(original).renameSpeaker("a", "Анна");
    var intermediate = editor.build();
    var result = editor.renameSpeaker("b", "Борис").renameSpeaker("a", "Аня").build();
    assertEquals(
        List.of(new Transcript.Speaker("a", "Аня"), new Transcript.Speaker("b", "Борис")),
        result.speakers());
    assertEquals("Анна", intermediate.speakers().get(0).displayName());
    assertEquals("Второй", intermediate.speakers().get(1).displayName());
    assertEquals("Первый", original.speakers().get(0).displayName());
  }
}
