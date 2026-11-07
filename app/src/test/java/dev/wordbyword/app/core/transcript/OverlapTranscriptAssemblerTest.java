package dev.wordbyword.app.core.transcript;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recording.Recording;
import java.net.URI;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class OverlapTranscriptAssemblerTest {
  private final OverlapTranscriptAssembler assembler = new OverlapTranscriptAssembler();

  private final ProcessingContext context =
      new ProcessingContext() {
        @Override
        public boolean isCancellationRequested() {
          return false;
        }

        @Override
        public void reportProgress(String stage, OptionalDouble fraction) {}
      };

  private Recording recording(OptionalLong durationMs) {
    return new Recording("rec-1", URI.create("content://example/audio/1"), "Встреча", durationMs);
  }

  private RecognitionResult.Segment segment(String text, long startMs, long endMs) {
    return new RecognitionResult.Segment(text, startMs, endMs);
  }

  private SpeakerDiarizer.Segment speaker(String id, long startMs, long endMs) {
    return new SpeakerDiarizer.Segment(id, startMs, endMs);
  }

  @Test
  public void mergesAdjacentSameSpeakerAndSplitsOnSpeakerChange() throws ProcessingException {
    var recognition =
        new RecognitionResult(
            "Привет как дела хорошо, а у тебя",
            List.of(
                segment("Привет", 0, 1000),
                segment("как дела", 1000, 2500),
                segment("хорошо, а у тебя", 3000, 5000)),
            RecognitionResult.Timing.SEGMENT);
    var diarization = List.of(speaker("A", 0, 2600), speaker("Б", 2700, 5200));

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, diarization, context);

    assertEquals(2, transcript.utterances().size());
    var first = transcript.utterances().get(0);
    assertEquals("A", first.speakerId().orElseThrow());
    assertEquals("Привет как дела", first.text());
    assertEquals(0, first.startMs());
    assertEquals(2500, first.endMs());

    var second = transcript.utterances().get(1);
    assertEquals("Б", second.speakerId().orElseThrow());
    assertEquals("хорошо, а у тебя", second.text());
    assertEquals(3000, second.startMs());
    assertEquals(5000, second.endMs());

    assertEquals(
        List.of(
            new Transcript.Speaker("A", "Участник 1"), new Transcript.Speaker("Б", "Участник 2")),
        transcript.speakers());
  }

  @Test
  public void longPauseSplitsUtteranceEvenForSameSpeaker() throws ProcessingException {
    var recognition =
        new RecognitionResult(
            "да ну",
            List.of(segment("да", 0, 500), segment("ну", 3000, 3400)),
            RecognitionResult.Timing.SEGMENT);
    var diarization = List.of(speaker("A", 0, 4000));

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, diarization, context);

    assertEquals(2, transcript.utterances().size());
    assertEquals("да", transcript.utterances().get(0).text());
    assertEquals("ну", transcript.utterances().get(1).text());
    assertEquals("A", transcript.utterances().get(0).speakerId().orElseThrow());
    assertEquals("A", transcript.utterances().get(1).speakerId().orElseThrow());
  }

  @Test
  public void unknownSpeakerFragmentsAreNeverMergedWithEachOther() throws ProcessingException {
    var recognition =
        new RecognitionResult(
            "один два",
            List.of(segment("один", 0, 500), segment("два", 500, 1000)),
            RecognitionResult.Timing.SEGMENT);

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, List.of(), context);

    assertEquals(2, transcript.utterances().size());
    assertTrue(transcript.utterances().get(0).speakerId().isEmpty());
    assertTrue(transcript.utterances().get(1).speakerId().isEmpty());
    assertTrue(transcript.speakers().isEmpty());
  }

  @Test
  public void segmentInGapBetweenSpeakersGoesToTheNearestOne() throws ProcessingException {
    var recognition =
        new RecognitionResult(
            "пауза", List.of(segment("пауза", 1200, 1400)), RecognitionResult.Timing.SEGMENT);
    var diarization = List.of(speaker("A", 0, 1000), speaker("B", 2000, 3000));

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, diarization, context);

    assertEquals("A", transcript.utterances().get(0).speakerId().orElseThrow());
  }

  @Test
  public void exactOverlapTieBreaksTowardsTheEarlierSpeaker() throws ProcessingException {
    var recognition =
        new RecognitionResult(
            "слово", List.of(segment("слово", 500, 1500)), RecognitionResult.Timing.SEGMENT);
    var diarization = List.of(speaker("B", 1000, 2000), speaker("A", 0, 1000));

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, diarization, context);

    assertEquals("A", transcript.utterances().get(0).speakerId().orElseThrow());
  }

  @Test
  public void unavailableTimingProducesOneUnattributedUtteranceSpanningTheRecording()
      throws ProcessingException {
    var recognition =
        new RecognitionResult("весь текст", List.of(), RecognitionResult.Timing.UNAVAILABLE);

    Transcript transcript =
        assembler.assemble(
            recording(OptionalLong.of(5000)), recognition, List.of(speaker("A", 0, 5000)), context);

    assertEquals(1, transcript.utterances().size());
    var utterance = transcript.utterances().get(0);
    assertTrue(utterance.speakerId().isEmpty());
    assertEquals("весь текст", utterance.text());
    assertEquals(0, utterance.startMs());
    assertEquals(5000, utterance.endMs());
  }

  @Test
  public void emptyTextAndSegmentsProduceAnEmptyTranscript() throws ProcessingException {
    var recognition = new RecognitionResult("", List.of(), RecognitionResult.Timing.UNAVAILABLE);

    Transcript transcript =
        assembler.assemble(recording(OptionalLong.empty()), recognition, List.of(), context);

    assertTrue(transcript.utterances().isEmpty());
    assertTrue(transcript.speakers().isEmpty());
  }

  @Test
  public void cancellationDuringAssemblyDoesNotReturnPartialTranscript() {
    var recognition =
        new RecognitionResult(
            "слово слово",
            List.of(segment("слово", 0, 1000), segment("слово", 1000, 2000)),
            RecognitionResult.Timing.SEGMENT);
    var cancellation =
        new ProcessingContext() {
          int checks;

          public boolean isCancellationRequested() {
            return ++checks > 2;
          }

          public void reportProgress(String stage, OptionalDouble fraction) {}
        };
    assertThrows(
        CancellationException.class,
        () ->
            assembler.assemble(
                recording(OptionalLong.of(2000)), recognition,
                List.of(speaker("A", 0, 1000), speaker("B", 1000, 2000)), cancellation));
  }
}
