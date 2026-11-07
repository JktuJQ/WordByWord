package dev.wordbyword.app.processing;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.OverlapTranscriptAssembler;
import dev.wordbyword.app.core.transcript.TranscriptAssembler;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class LocalTranscriptionPipelineTest {
  private final List<String> events = new ArrayList<>();
  private boolean cancelled;
  private final ProcessingContext context =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return cancelled;
        }

        public void reportProgress(String stage, OptionalDouble fraction) {}
      };
  private final PcmAudio audio =
      new PcmAudio() {
        public OptionalLong sampleCount() {
          return OptionalLong.of(16000);
        }

        public Reader openReader() {
          throw new AssertionError("Stages must receive same PCM");
        }

        public void close() {
          events.add("close");
        }
      };

  private RecognitionResult recognition() {
    return new RecognitionResult(
        "Привет",
        List.of(new RecognitionResult.Segment("Привет", 100, 900)),
        RecognitionResult.Timing.SEGMENT);
  }

  private Recording recording() {
    return new Recording(
        "sample", URI.create("file:///sample.wav"), "Разговор", OptionalLong.empty());
  }

  @Test
  public void runsModelsSequentiallyOnSamePcmThenAssemblesAndCloses() throws Exception {
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> {
              events.add("prepare");
              return audio;
            },
            (input, ctx) -> {
              assertSame(audio, input);
              events.add("asr");
              return recognition();
            },
            (input, ctx) -> {
              assertSame(audio, input);
              events.add("diarize");
              return List.of(new SpeakerDiarizer.Segment("speaker_0", 0, 1000));
            },
            (recording, recognition, diarization, ctx) -> {
              events.add("assemble");
              return new OverlapTranscriptAssembler()
                  .assemble(recording, recognition, diarization, ctx);
            });
    var transcript = pipeline.transcribe(recording(), context);
    assertEquals(List.of("prepare", "asr", "diarize", "assemble", "close"), events);
    assertEquals(1000, transcript.recording().durationMs().getAsLong());
    assertEquals("speaker_0", transcript.utterances().get(0).speakerId().orElseThrow());
    assertEquals("Участник 1", transcript.speakers().get(0).displayName());
    assertEquals("Привет", transcript.utterances().get(0).text());
  }

  @Test
  public void cancellationAfterAsrSkipsDiarizationAndClosesAudio() {
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              cancelled = true;
              return recognition();
            },
            (input, ctx) -> {
              throw new AssertionError("Diarizer must not start");
            },
            new OverlapTranscriptAssembler());
    assertThrows(CancellationException.class, () -> pipeline.transcribe(recording(), context));
    assertEquals(List.of("close"), events);
  }

  @Test
  public void cancellationAfterDiarizationSkipsAssemblerAndClosesAudio() {
    TranscriptAssembler assembler =
        (recording, result, speakers, ctx) -> {
          throw new AssertionError("Assembler must not start");
        };
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> recognition(),
            (input, ctx) -> {
              cancelled = true;
              return List.of();
            },
            assembler);
    assertThrows(CancellationException.class, () -> pipeline.transcribe(recording(), context));
    assertEquals(List.of("close"), events);
  }

  @Test
  public void failedDiarizationDoesNotProducePartialTranscript() {
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> recognition(),
            (input, ctx) -> {
              throw new ProcessingException("Diarization failed");
            },
            new OverlapTranscriptAssembler());
    var error =
        assertThrows(ProcessingException.class, () -> pipeline.transcribe(recording(), context));
    assertEquals("Diarization failed", error.getMessage());
    assertEquals(List.of("close"), events);
  }
}
