package dev.wordbyword.app.ui;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.core.transcript.TranscriptEditor;
import dev.wordbyword.app.ui.AppState.Item;
import dev.wordbyword.app.ui.AppState.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class LibraryService {
  private static final Logger LOG = Logger.getLogger(LibraryService.class.getName());
  private final TranscriptRepository repository;
  private final Executor worker;
  private final Executor ui;

  LibraryService(TranscriptRepository repository, Executor worker, Executor ui) {
    this.repository = repository;
    this.worker = worker;
    this.ui = ui;
  }

  void load(Consumer<List<Item>> success, Consumer<String> failure) {
    execute(
        () -> {
          var items = new ArrayList<Item>();
          for (Recording recording : repository.listRecordings()) {
            try {
              Transcript transcript = repository.find(recording.id()).orElse(null);
              items.add(
                  new Item(
                      recording,
                      transcript == null ? Status.IMPORTED : Status.READY,
                      transcript == null ? "Готово к запуску" : "Стенограмма готова",
                      OptionalDouble.empty(),
                      transcript));
            } catch (IOException exception) {
              LOG.log(Level.WARNING, "Cannot load recording " + recording.id(), exception);
              items.add(
                  new Item(
                      recording,
                      Status.ERROR,
                      "Сохранённая запись недоступна",
                      OptionalDouble.empty(),
                      null));
            }
          }
          return items;
        },
        success,
        failure,
        "Не удалось загрузить библиотеку");
  }

  void rename(
      String recordingId,
      String speakerId,
      String name,
      Consumer<Transcript> success,
      Consumer<String> failure) {
    execute(
        () ->
            repository.update(
                recordingId,
                transcript ->
                    new TranscriptEditor(transcript).renameSpeaker(speakerId, name).build()),
        success,
        failure,
        "Не удалось сохранить имя участника");
  }

  void delete(Set<String> ids, Consumer<String> success, BiConsumer<String, String> failure) {
    for (String id : ids)
      execute(
          () -> {
            repository.delete(id);
            return id;
          },
          success,
          error -> failure.accept(id, error),
          "Не удалось удалить запись");
  }

  private <T> void execute(
      Callable<T> operation, Consumer<T> success, Consumer<String> failure, String message) {
    worker.execute(
        () -> {
          try {
            T value = operation.call();
            ui.execute(() -> success.accept(value));
          } catch (Exception exception) {
            LOG.log(Level.WARNING, message, exception);
            ui.execute(() -> failure.accept(message));
          }
        });
  }
}
