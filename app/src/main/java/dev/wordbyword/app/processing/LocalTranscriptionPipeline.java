package dev.wordbyword.app.processing;

import dev.wordbyword.app.core.audio.AudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer.Segment;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recognition.SpeechRecognizer;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.core.transcript.TranscriptAssembler;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class LocalTranscriptionPipeline implements TranscriptionPipeline {
  private static final Logger LOG = Logger.getLogger(LocalTranscriptionPipeline.class.getName());

  private record Outcome<T>(T value, Throwable failure) {}

  private final AudioPreparer audioPreparer;
  private final SpeechRecognizer recognizer;
  private final SpeakerDiarizer diarizer;
  private final TranscriptAssembler assembler;
  private final Executor diarizationExecutor;

  public LocalTranscriptionPipeline(
      AudioPreparer audioPreparer,
      SpeechRecognizer recognizer,
      SpeakerDiarizer diarizer,
      TranscriptAssembler assembler,
      Executor diarizationExecutor) {
    this.audioPreparer = audioPreparer;
    this.recognizer = recognizer;
    this.diarizer = diarizer;
    this.assembler = assembler;
    this.diarizationExecutor = diarizationExecutor;
  }

  @Override
  public Transcript transcribe(Recording recording, ProcessingContext context)
      throws ProcessingException {
    long started = System.nanoTime();
    context.throwIfCancelled();
    context.reportProgress("Подготавливаем аудио", OptionalDouble.empty());
    try (var audio = audioPreparer.prepare(recording.audioUri(), context)) {
      context.throwIfCancelled();
      var stop = new AtomicBoolean();
      var progress = new ModelProgress(context, stop);
      var diarization = new CompletableFuture<Outcome<List<Segment>>>();
      try {
        diarizationExecutor.execute(
            () ->
                diarization.complete(
                    runStage(
                        "diarization",
                        stop,
                        progress.diarization,
                        () -> diarizer.diarize(audio, progress.diarization))));
      } catch (RuntimeException exception) {
        throw new ProcessingException("Не удалось запустить диаризацию", exception);
      }
      Outcome<RecognitionResult> recognition =
          runStage("asr", stop, progress.asr, () -> recognizer.transcribe(audio, progress.asr));
      Outcome<List<Segment>> speakers = diarization.join();
      context.throwIfCancelled();
      if (recognition.failure() != null
          && !(recognition.failure() instanceof CancellationException))
        rethrow(recognition.failure());
      if (speakers.failure() != null) rethrow(speakers.failure());
      if (recognition.failure() != null) rethrow(recognition.failure());
      var duration =
          audio.sampleCount().isPresent()
              ? OptionalLong.of(audio.sampleCount().getAsLong() * 1000 / PcmAudio.SAMPLE_RATE_HZ)
              : recording.durationMs();
      var completedRecording =
          new Recording(recording.id(), recording.audioUri(), recording.title(), duration);
      context.reportProgress("Собираем стенограмму", OptionalDouble.empty());
      var result =
          assembler.assemble(completedRecording, recognition.value(), speakers.value(), context);
      context.throwIfCancelled();
      LOG.info(
          String.format(
              Locale.ROOT,
              "pipeline recording=%s elapsed_ms=%d",
              recording.id(),
              (System.nanoTime() - started) / 1_000_000));
      return result;
    } catch (IOException exception) {
      throw new ProcessingException("Не удалось освободить временное аудио", exception);
    }
  }

  @FunctionalInterface
  private interface Stage<T> {
    T run() throws ProcessingException;
  }

  private static <T> Outcome<T> runStage(
      String name, AtomicBoolean stop, ProcessingContext context, Stage<T> stage) {
    long started = System.nanoTime();
    try {
      context.throwIfCancelled();
      T value = stage.run();
      context.reportProgress("Готово", OptionalDouble.of(1));
      return new Outcome<>(value, null);
    } catch (Throwable failure) {
      stop.set(true);
      return new Outcome<>(null, failure);
    } finally {
      LOG.info(
          String.format(
              Locale.ROOT,
              "stage=%s elapsed_ms=%d",
              name,
              (System.nanoTime() - started) / 1_000_000));
    }
  }

  private static void rethrow(Throwable failure) throws ProcessingException {
    if (failure instanceof ProcessingException exception) throw exception;
    if (failure instanceof RuntimeException exception) throw exception;
    if (failure instanceof Error error) throw error;
    throw new ProcessingException("Ошибка обработки аудио", failure);
  }

  private static final class ModelProgress {
    private final ProcessingContext parent;
    private final AtomicBoolean stop;
    private String asrStage = "ожидание";
    private String diarizationStage = "ожидание";
    final ProcessingContext asr = context(true);
    final ProcessingContext diarization = context(false);

    ModelProgress(ProcessingContext parent, AtomicBoolean stop) {
      this.parent = parent;
      this.stop = stop;
    }

    private ProcessingContext context(boolean recognition) {
      return new ProcessingContext() {
        public boolean isCancellationRequested() {
          return stop.get() || parent.isCancellationRequested();
        }

        public void reportProgress(String stage, OptionalDouble fraction) {
          update(recognition, stage, fraction);
        }
      };
    }

    private synchronized void update(boolean recognition, String stage, OptionalDouble fraction) {
      String detail = stage;
      if (fraction.isPresent() && Double.isFinite(fraction.getAsDouble()))
        detail += " " + Math.round(Math.max(0, Math.min(1, fraction.getAsDouble())) * 100) + "%";
      if (recognition) asrStage = detail;
      else diarizationStage = detail;
      parent.reportProgress(
          "ASR: " + asrStage + "; диаризация: " + diarizationStage, OptionalDouble.empty());
    }
  }
}
