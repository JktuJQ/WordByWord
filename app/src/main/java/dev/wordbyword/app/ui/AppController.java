package dev.wordbyword.app.ui;

import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.processing.ProcessingRunner;
import dev.wordbyword.app.ui.AppState.Item;
import dev.wordbyword.app.ui.AppState.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class AppController {
  private final ProcessingRunner processing;
  private final TranscriptRepository repository;
  private final Executor worker;
  private final Executor ui;
  private final Map<String, Item> items = new LinkedHashMap<>();
  private Consumer<AppState> listener;
  private String selectedId;
  private String message = "";

  public AppController(
      TranscriptRepository repository,
      TranscriptionPipeline pipeline,
      Executor worker,
      Executor ui) {
    this.repository = repository;
    this.worker = worker;
    this.ui = ui;
    processing = new ProcessingRunner(repository, pipeline, worker, ui);
  }

  public AppState state() {
    return new AppState(
        List.copyOf(items.values()),
        selectedId,
        processing.isBusy(),
        processing.isCancelling(),
        message);
  }

  public void attach(Consumer<AppState> listener) {
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

  public void removeVisible(Set<String> recordingIds) {
    if (processing.isBusy()) return;
    recordingIds.forEach(items::remove);
    if (recordingIds.contains(selectedId)) selectedId = null;
    emit();
  }

  public void renameSpeaker(String speakerId, String displayName) {
    String recordingId = selectedId;
    if (recordingId == null || speakerId == null || displayName == null || displayName.isBlank()) {
      return;
    }
    worker.execute(
        () -> {
          try {
            repository.renameSpeaker(recordingId, speakerId, displayName.trim());
            Transcript updated = repository.find(recordingId).orElseThrow();
            ui.execute(
                () -> {
                  Item old = items.get(recordingId);
                  if (old != null) {
                    items.put(
                        recordingId,
                        new Item(old.recording(), old.status(), old.detail(), old.progress(), updated));
                    emit();
                  }
                });
          } catch (Exception exception) {
            ui.execute(() -> notifyMessage("Не удалось сохранить имя участника"));
          }
        });
  }

  public void open(Recording recording) {
    add(recording);
    Item item = items.get(recording.id());
    if (item.status() == Status.READY) select(recording.id());
    else start(recording.id());
  }

  public void start(String recordingId) {
    Item item = items.get(recordingId);
    if (processing.isBusy() || item == null || item.status() == Status.READY) return;
    message = "";
    processing.start(
        item.recording(),
        new ProcessingRunner.Listener() {
          @Override
          public void onProgress(String stage, OptionalDouble fraction) {
            update(item.recording(), Status.PROCESSING, stage, fraction, null);
          }

          @Override
          public void onSuccess(Transcript transcript) {
            finish(transcript.recording(), Status.READY, "Стенограмма готова", transcript);
          }

          @Override
          public void onCancelled() {
            finish(item.recording(), Status.CANCELLED, "Обработка отменена", null);
          }

          @Override
          public void onFailure(Exception exception) {
            String detail = exception.getMessage();
            finish(
                item.recording(),
                Status.ERROR,
                detail == null || detail.isBlank() ? "Не удалось обработать запись" : detail,
                null);
          }
        });
  }

  public void cancel() {
    if (processing.cancel()) notifyMessage("Отменяем обработку…");
  }

  private void finish(Recording recording, Status status, String detail, Transcript transcript) {
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
