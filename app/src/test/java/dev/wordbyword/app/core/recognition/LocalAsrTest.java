package dev.wordbyword.app.core.recognition;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.OverlapTranscriptAssembler;
import dev.wordbyword.app.processing.LocalTranscriptionPipeline;
import java.net.URI;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public final class LocalAsrTest {
  private static final ProcessingContext CONTEXT =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return false;
        }

        public void reportProgress(String stage, OptionalDouble fraction) {}
      };

  @Test
  public void contextStaysInsideAudioAndDoesNotRepeatSamples() {
    var windows =
        SherpaSpeechRecognizer.addContext(
            List.of(
                new SherpaSpeechRecognizer.SpeechInterval(100, 16000),
                new SherpaSpeechRecognizer.SpeechInterval(17000, 30000)),
            31000);
    assertEquals(0, windows.get(0).start());
    assertEquals(16500, windows.get(0).end());
    assertEquals(16500, windows.get(1).start());
    assertEquals(31000, windows.get(1).end());
    assertTrue(SherpaSpeechRecognizer.addContext(List.of(), 0).isEmpty());
  }

  @Test
  public void pipelineUsesRealRecognitionAndLeavesSpeakerUnknown() throws Exception {
    var audio = new Audio();
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, context) -> audio,
            (pcm, context) ->
                new RecognitionResult(
                    "привет",
                    List.of(new RecognitionResult.Segment("привет", 100, 900)),
                    RecognitionResult.Timing.SEGMENT),
            (pcm, context) -> List.of(),
            new OverlapTranscriptAssembler(),
            Runnable::run);
    var result = pipeline.transcribe(recording(), CONTEXT);
    assertEquals("привет", result.utterances().get(0).text());
    assertTrue(result.utterances().get(0).speakerId().isEmpty());
    assertTrue(result.speakers().isEmpty());
    assertEquals(1000, result.recording().durationMs().getAsLong());
    assertTrue(audio.closed);
  }

  @Test
  public void cancellationClosesPcmAndDoesNotReturnTranscript() throws Exception {
    var audio = new Audio();
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, context) -> audio,
            (pcm, context) -> {
              throw new CancellationException();
            },
            (pcm, context) -> List.of(),
            new OverlapTranscriptAssembler(),
            Runnable::run);
    assertThrows(CancellationException.class, () -> pipeline.transcribe(recording(), CONTEXT));
    assertTrue(audio.closed);
  }

  private static Recording recording() {
    return new Recording("test", URI.create("file:///test.wav"), "test", OptionalLong.empty());
  }

  private static final class Audio implements PcmAudio {
    boolean closed;

    public OptionalLong sampleCount() {
      return OptionalLong.of(16000);
    }

    public Reader openReader() {
      throw new AssertionError("Fake recognizer must not read");
    }

    public void close() {
      closed = true;
    }
  }
}
