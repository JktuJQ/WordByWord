package dev.wordbyword.app.processing;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.OverlapTranscriptAssembler;
import java.net.URI;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public class LocalTranscriptionPipelineTest {
  private final ExecutorService models = Executors.newSingleThreadExecutor();
  private final ExecutorService processing = Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ProcessingContext context =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return cancelled.get();
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
          closed.set(true);
        }
      };

  @After
  public void stopExecutors() throws Exception {
    models.shutdownNow();
    processing.shutdownNow();
    assertTrue(models.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(processing.awaitTermination(5, TimeUnit.SECONDS));
  }

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

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Stage timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new CancellationException();
    }
  }

  @Test(timeout = 15000)
  public void modelsOverlapOnSamePcmAndAssemblerWaitsForBoth() throws Exception {
    var asrStarted = new CountDownLatch(1);
    var diarizationStarted = new CountDownLatch(1);
    var asrDone = new AtomicBoolean();
    var diarizationDone = new AtomicBoolean();
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              assertSame(audio, input);
              asrStarted.countDown();
              await(diarizationStarted);
              asrDone.set(true);
              return recognition();
            },
            (input, ctx) -> {
              assertSame(audio, input);
              diarizationStarted.countDown();
              await(asrStarted);
              diarizationDone.set(true);
              return List.of(new SpeakerDiarizer.Segment("speaker_0", 0, 1000));
            },
            (recording, result, speakers, ctx) -> {
              assertTrue(asrDone.get());
              assertTrue(diarizationDone.get());
              assertFalse(closed.get());
              return new OverlapTranscriptAssembler().assemble(recording, result, speakers, ctx);
            },
            models);
    var transcript = pipeline.transcribe(recording(), context);
    assertEquals("speaker_0", transcript.utterances().get(0).speakerId().orElseThrow());
    assertEquals(1000, transcript.recording().durationMs().getAsLong());
    assertTrue(closed.get());
  }

  @Test(timeout = 15000)
  public void asrFailureWaitsForUninterruptibleDiarizationBeforeClosingPcm() throws Exception {
    var started = new CountDownLatch(1);
    var failed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              await(started);
              failed.countDown();
              throw new ProcessingException("ASR failed");
            },
            (input, ctx) -> {
              started.countDown();
              await(release);
              assertFalse(closed.get());
              ctx.throwIfCancelled();
              return List.of();
            },
            new OverlapTranscriptAssembler(),
            models);
    var result = processing.submit(() -> pipeline.transcribe(recording(), context));
    try {
      await(failed);
      assertFalse(closed.get());
      assertFalse(result.isDone());
    } finally {
      release.countDown();
    }
    var exception = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
    assertEquals("ASR failed", exception.getCause().getMessage());
    assertTrue(closed.get());
  }

  @Test(timeout = 15000)
  public void diarizationFailureStopsAsrAndPreservesOriginalCause() {
    var started = new CountDownLatch(1);
    var failure = new ProcessingException("Diarization failed");
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              started.countDown();
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
              while (!ctx.isCancellationRequested()) {
                if (System.nanoTime() > deadline) throw new AssertionError("ASR not stopped");
                Thread.yield();
              }
              ctx.throwIfCancelled();
              return recognition();
            },
            (input, ctx) -> {
              await(started);
              throw failure;
            },
            new OverlapTranscriptAssembler(),
            models);
    assertSame(
        failure,
        assertThrows(ProcessingException.class, () -> pipeline.transcribe(recording(), context)));
    assertTrue(closed.get());
  }

  @Test(timeout = 15000)
  public void cancellationWaitsForBothBranchesAndDoesNotAssemble() throws Exception {
    var started = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              started.countDown();
              await(release);
              return recognition();
            },
            (input, ctx) -> {
              started.countDown();
              await(release);
              return List.of();
            },
            (recording, result, speakers, ctx) -> {
              throw new AssertionError("Assembly after cancellation");
            },
            models);
    var result = processing.submit(() -> pipeline.transcribe(recording(), context));
    try {
      await(started);
      cancelled.set(true);
      assertFalse(closed.get());
      assertFalse(result.isDone());
    } finally {
      release.countDown();
    }
    var exception = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
    assertTrue(exception.getCause() instanceof CancellationException);
    assertTrue(closed.get());
  }

  @Test
  public void rejectedModelTaskClosesPcmWithoutStartingAsr() {
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> {
              throw new AssertionError("Unexpected ASR");
            },
            (input, ctx) -> List.of(),
            new OverlapTranscriptAssembler(),
            task -> {
              throw new RejectedExecutionException();
            });
    assertThrows(ProcessingException.class, () -> pipeline.transcribe(recording(), context));
    assertTrue(closed.get());
  }

  @Test
  public void directExecutorProvidesSequentialBaselineWithSameOutput() throws Exception {
    var pipeline =
        new LocalTranscriptionPipeline(
            (uri, ctx) -> audio,
            (input, ctx) -> recognition(),
            (input, ctx) -> List.of(new SpeakerDiarizer.Segment("speaker_0", 0, 1000)),
            new OverlapTranscriptAssembler(),
            Runnable::run);
    var transcript = pipeline.transcribe(recording(), context);
    assertEquals("speaker_0", transcript.utterances().get(0).speakerId().orElseThrow());
    assertTrue(closed.get());
  }
}
