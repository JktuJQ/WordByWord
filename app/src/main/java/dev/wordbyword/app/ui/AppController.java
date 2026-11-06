package dev.wordbyword.app.ui;

import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class AppController {
  public enum Status {
    IMPORTED,
    PROCESSING,
    READY,
    ERROR,
    CANCELLED
  }

  public record Item(
      Recording recording,
      Status status,
      String detail,
      OptionalDouble progress,
      Transcript transcript) {}

  public record State(
      List<Item> items, String selectedId, boolean busy, boolean cancelling, String message) {
    public State {
      items = List.copyOf(items);
    }

    public Item selected() {
      return items.stream()
          .filter(item -> item.recording().id().equals(selectedId))
          .findFirst()
          .orElse(null);
    }
  }

  private enum TaskStatus {
    RUNNING,
    CANCELLED,
    SAVING
  }

  private final TranscriptRepository repository;
  private final TranscriptionPipeline pipeline;
  private final Executor worker;
  private final Executor ui;
  private final Map<String, Item> items = new LinkedHashMap<>();
  private Consumer<State> listener;
  private String selectedId;
  private String message = "";
  private AtomicReference<TaskStatus> task;

  public AppController(
      TranscriptRepository repository,
      TranscriptionPipeline pipeline,
      Executor worker,
      Executor ui) {
    this.repository = repository;
    this.pipeline = pipeline;
    this.worker = worker;
    this.ui = ui;
  }

  public State state() {
    return new State(
        List.copyOf(items.values()),
        selectedId,
        task != null,
        task != null && task.get() == TaskStatus.CANCELLED,
        message);
  }

  public void attach(Consumer<State> listener) {
    this.listener = listener;
    emit();
  }

  public void detach() {
    listener = null;
  }

  public void add(Recording recording) {
    items.putIfAbsent(
        recording.id(),
        new Item(recording, Status.IMPORTED, "Готово к запуску", OptionalDouble.empty(), null));
    message = "";
    emit();
  }

  public void select(String recordingId) {
    Item item = items.get(recordingId);
    if (item != null && item.transcript() != null) {
      selectedId = recordingId;
      message = "";
      emit();
    }
  }

  public void showLibrary() {
    selectedId = null;
    emit();
  }

  public void notifyMessage(String text) {
    message = text;
    emit();
  }

  public void start(String recordingId) {
    Item item = items.get(recordingId);
    if (task != null || item == null || item.status() == Status.READY) {
      return;
    }
    var token = new AtomicReference<>(TaskStatus.RUNNING);
    task = token;
    message = "";
    update(item.recording(), Status.PROCESSING, "Запуск", OptionalDouble.empty(), null);
    worker.execute(
        () -> {
          try {
            Transcript result =
                pipeline.transcribe(
                    item.recording(),
                    new ProcessingContext() {
                      @Override
                      public boolean isCancellationRequested() {
                        return token.get() == TaskStatus.CANCELLED;
                      }

                      @Override
                      public void reportProgress(String stage, OptionalDouble fraction) {
                        ui.execute(
                            () -> {
                              if (task == token && token.get() == TaskStatus.RUNNING) {
                                update(item.recording(), Status.PROCESSING, stage, fraction, null);
                              }
                            });
                      }
                    });
            if (!token.compareAndSet(TaskStatus.RUNNING, TaskStatus.SAVING)) {
              throw new CancellationException();
            }
            Transcript saved = repository.save(result);
            ui.execute(
                () -> finish(token, saved.recording(), Status.READY, "Стенограмма готова", saved));
          } catch (CancellationException exception) {
            ui.execute(
                () ->
                    finish(token, item.recording(), Status.CANCELLED, "Обработка отменена", null));
          } catch (Exception exception) {
            ui.execute(
                () ->
                    finish(
                        token,
                        item.recording(),
                        Status.ERROR,
                        exception.getMessage() == null
                            ? "Не удалось обработать запись"
                            : exception.getMessage(),
                        null));
          }
        });
  }

  public void cancel() {
    if (task != null && task.compareAndSet(TaskStatus.RUNNING, TaskStatus.CANCELLED)) {
      message = "Отменяем обработку…";
      emit();
    }
  }

  private void finish(
      AtomicReference<TaskStatus> token,
      Recording recording,
      Status status,
      String detail,
      Transcript transcript) {
    if (task != token) {
      return;
    }
    task = null;
    message = "";
    update(recording, status, detail, OptionalDouble.empty(), transcript);
  }

  private void update(
      Recording recording,
      Status status,
      String detail,
      OptionalDouble progress,
      Transcript transcript) {
    items.put(recording.id(), new Item(recording, status, detail, progress, transcript));
    emit();
  }

  private void emit() {
    if (listener != null) {
      listener.accept(state());
    }
  }
}
