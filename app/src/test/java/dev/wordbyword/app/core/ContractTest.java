package dev.wordbyword.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.wordbyword.app.core.model.RecognitionResult;
import dev.wordbyword.app.core.model.Recording;
import dev.wordbyword.app.core.model.Speaker;
import dev.wordbyword.app.core.model.TimeRange;
import dev.wordbyword.app.core.model.TimedText;
import dev.wordbyword.app.core.model.Transcript;
import dev.wordbyword.app.core.model.Utterance;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingProgress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class ContractTest {
  @Test
  public void transcriptSnapshotCannotBeChangedThroughProducerCollections() {
    var speakers = new ArrayList<>(List.of(new Speaker("a", "Анна")));
    var utterances =
        new ArrayList<>(
            List.of(new Utterance(Optional.of("a"), "Привет", new TimeRange(1000, 2000))));
    var transcript = new Transcript(speakers, utterances);

    speakers.clear();
    utterances.clear();

    assertEquals("Анна", transcript.speakers().get(0).displayName());
    assertEquals("Привет", transcript.utterances().get(0).text());
    assertThrows(UnsupportedOperationException.class, () -> transcript.utterances().clear());
  }

  @Test
  public void uncertainSpeakerIsExplicitButDanglingSpeakerReferencesAreRejected() {
    var unknown = new Utterance(Optional.empty(), "Неясная реплика", new TimeRange(0, 500));
    assertTrue(
        new Transcript(List.of(), List.of(unknown)).utterances().get(0).speakerId().isEmpty());

    var dangling = new Utterance(Optional.of("missing"), "Текст", new TimeRange(0, 500));
    assertThrows(
        IllegalArgumentException.class, () -> new Transcript(List.of(), List.of(dangling)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Transcript(
                List.of(new Speaker("a", "Анна"), new Speaker("a", "Борис")), List.of()));
  }

  @Test
  public void renamingDoesNotChangeSpeakerIdentityOrRequireGlobalIds() {
    var time = new TimeRange(0, 1000);
    var utterance = new Utterance(Optional.of("1"), "Текст", time);
    var firstRecording = new Transcript(List.of(new Speaker("1", "Анна")), List.of(utterance));
    var secondRecording = new Transcript(List.of(new Speaker("1", "Борис")), List.of(utterance));
    var renamed =
        new Transcript(List.of(new Speaker("1", "Анна Петрова")), firstRecording.utterances());

    assertEquals(firstRecording.utterances(), renamed.utterances());
    assertEquals("Анна", firstRecording.speakers().get(0).displayName());
    assertEquals("Борис", secondRecording.speakers().get(0).displayName());
    assertEquals("Анна Петрова", renamed.speakers().get(0).displayName());
  }

  @Test
  public void coarseOrMissingModelTimingsDoNotRequireInventedWordTimestamps() {
    var plain =
        new RecognitionResult(
            "Привет, мир", List.of(), RecognitionResult.TimingGranularity.UNAVAILABLE);
    var segments =
        new ArrayList<>(List.of(new TimedText("Привет, мир", new TimeRange(1500, 3000))));
    var coarse =
        new RecognitionResult("Привет, мир", segments, RecognitionResult.TimingGranularity.SEGMENT);
    segments.clear();

    assertTrue(plain.fragments().isEmpty());
    assertEquals(new TimeRange(1500, 3000), coarse.fragments().get(0).time());
    assertEquals(RecognitionResult.TimingGranularity.SEGMENT, coarse.timingGranularity());
    var recording =
        new Recording(
            "r1", URI.create("content://example/audio/7"), "Встреча", OptionalLong.empty());
    assertFalse(recording.durationMs().isPresent());
  }

  @Test
  public void cancellationIsDistinctFromSuccessfulEmptyOutput() {
    var cancelled = new AtomicBoolean(false);
    var progress = new ArrayList<ProcessingProgress>();
    ProcessingContext context =
        new ProcessingContext() {
          @Override
          public boolean isCancellationRequested() {
            return cancelled.get();
          }

          @Override
          public void reportProgress(ProcessingProgress update) {
            progress.add(update);
          }
        };

    context.throwIfCancelled();
    context.reportProgress(new ProcessingProgress("recognition", OptionalDouble.empty()));
    cancelled.set(true);

    assertThrows(CancellationException.class, context::throwIfCancelled);
    assertTrue(progress.get(0).fraction().isEmpty());
    assertTrue(new Transcript(List.of(), List.of()).utterances().isEmpty());
  }
}
