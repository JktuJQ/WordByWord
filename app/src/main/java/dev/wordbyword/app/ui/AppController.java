package dev.wordbyword.app.ui;

import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.processing.ProcessingRunner;
import dev.wordbyword.app.ui.AppState.Item;
import dev.wordbyword.app.ui.AppState.Status;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class AppController {
  private final ProcessingRunner processing;
  private final LibraryService library;
  private final Set<String> deleting = new HashSet<>();
  private final Map<String, Item> items = new LinkedHashMap<>();
  private Consumer<AppState> listener;
  private String selectedId;
  private String message = "";

  public AppController(
      TranscriptRepository repository,
      TranscriptionPipeline pipeline,
      Executor processingExecutor,
      Executor ioExecutor,
      Executor ui) {
    library = new LibraryService(repository, ioExecutor, ui);
    processing = new ProcessingRunner(repository, pipeline, processingExecutor, ui);
  }

  public AppState state() {
    return new AppState(
        List.copyOf(items.values()),
        selectedId,
        processing.isBusy() || !deleting.isEmpty(),
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

  public void clearMessage() {
    if (message.isEmpty()) return;
    message = "";
    emit();
  }

  public void notifyMessage(String text) {
    message = text;
    emit();
  }

  public void load() {
    library.load(
        loaded -> {
          for (Item item : loaded) items.putIfAbsent(item.recording().id(), item);
          emit();
        },
        this::notifyMessage);
  }

  public boolean canDelete(Set<String> recordingIds) {
    return !recordingIds.isEmpty()
        && deleting.isEmpty()
        && recordingIds.stream()
            .allMatch(
                id -> {
                  Item item = items.get(id);
                  return item != null && item.status() != Status.PROCESSING;
                });
  }

  public void delete(Set<String> recordingIds) {
    if (!canDelete(recordingIds)) return;
    deleting.addAll(recordingIds);
    emit();
    library.delete(
        Set.copyOf(recordingIds),
        id -> {
          deleting.remove(id);
          items.remove(id);
          if (id.equals(selectedId)) selectedId = null;
          emit();
        },
        (id, error) -> {
          deleting.remove(id);
          notifyMessage(error);
        });
  }

  public void renameSpeaker(String speakerId, String displayName) {
    String recordingId = selectedId;
    if (recordingId == null || speakerId == null || displayName == null || displayName.isBlank())
      return;
    library.rename(
        recordingId,
        speakerId,
        displayName.trim(),
        updated -> {
          Item old = items.get(recordingId);
          if (old != null) {
            items.put(
                recordingId,
                new Item(updated.recording(), old.status(), old.detail(), old.progress(), updated));
            emit();
          }
        },
        this::notifyMessage);
  }

  public void start(String recordingId) {
    Item item = items.get(recordingId);
    if (processing.isBusy() || !deleting.isEmpty() || item == null || item.status() == Status.READY)
      return;
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
