package dev.wordbyword.app.processing;

import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProcessingRunner {
  private static final Logger LOG = Logger.getLogger(ProcessingRunner.class.getName());

  public interface Listener {
    void onProgress(String stage, OptionalDouble fraction);

    void onSuccess(Transcript transcript);

    void onCancelled();

    void onFailure(Exception exception);
  }

  private enum Status {
    RUNNING,
    CANCELLED,
    SAVING
  }

  private final TranscriptRepository repository;
  private final TranscriptionPipeline pipeline;
  private final Executor worker;
  private final Executor ui;
  private AtomicReference<Status> task;

  public ProcessingRunner(
      TranscriptRepository repository,
      TranscriptionPipeline pipeline,
      Executor worker,
      Executor ui) {
    this.repository = repository;
    this.pipeline = pipeline;
    this.worker = worker;
    this.ui = ui;
  }

  public boolean isBusy() {
    return task != null;
  }

  public boolean isCancelling() {
    return task != null && task.get() == Status.CANCELLED;
  }

  public void start(Recording recording, Listener listener) {
    if (isBusy()) return;
    var token = new AtomicReference<>(Status.RUNNING);
    task = token;
    listener.onProgress("Запуск", OptionalDouble.empty());
    try {
      worker.execute(() -> run(recording, listener, token));
    } catch (RejectedExecutionException exception) {
      complete(token, () -> listener.onFailure(exception));
    }
  }

  private void run(Recording recording, Listener listener, AtomicReference<Status> token) {
    ProcessingContext context =
        new ProcessingContext() {
          @Override
          public boolean isCancellationRequested() {
            return token.get() == Status.CANCELLED;
          }

          @Override
          public void reportProgress(String stage, OptionalDouble fraction) {
            ui.execute(
                () -> {
                  if (task == token && token.get() == Status.RUNNING)
                    listener.onProgress(stage, fraction);
                });
          }
        };
    try {
      context.throwIfCancelled();
      Recording savedRecording = repository.saveRecording(recording);
      context.throwIfCancelled();
      Transcript result = pipeline.transcribe(savedRecording, context);
      if (!token.compareAndSet(Status.RUNNING, Status.SAVING)) throw new CancellationException();
      ui.execute(
          () -> {
            if (task == token) listener.onProgress("Сохраняем стенограмму", OptionalDouble.empty());
          });
      Transcript saved = repository.save(result);
      complete(token, () -> listener.onSuccess(saved));
    } catch (CancellationException exception) {
      complete(token, listener::onCancelled);
    } catch (Exception exception) {
      if (token.get() == Status.CANCELLED) {
        complete(token, listener::onCancelled);
      } else {
        LOG.log(Level.WARNING, "Cannot process recording " + recording.id(), exception);
        complete(token, () -> listener.onFailure(exception));
      }
    }
  }

  public boolean cancel() {
    return task != null && task.compareAndSet(Status.RUNNING, Status.CANCELLED);
  }

  private void complete(AtomicReference<Status> token, Runnable callback) {
    ui.execute(
        () -> {
          if (task != token) return;
          task = null;
          callback.run();
        });
  }
}
