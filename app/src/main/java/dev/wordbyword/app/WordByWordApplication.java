package dev.wordbyword.app;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.storage.FileTranscriptRepository;
import dev.wordbyword.app.ui.AppController;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WordByWordApplication extends Application {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AppController controller;
  private TranscriptRepository repository;

  @Override
  public void onCreate() {
    super.onCreate();
    TranscriptionPipeline pipeline =
        (recording, context) -> {
          throw new ProcessingException("Конвейер распознавания ещё не подключён");
        };
    var handler = new Handler(Looper.getMainLooper());
    repository =
        new FileTranscriptRepository(
            getFilesDir().toPath().resolve("library"),
            uri -> getContentResolver().openInputStream(Uri.parse(uri.toString())),
            exception ->
                handler.post(
                    () -> controller.notifyMessage("Некоторые записи повреждены и не загружены")));
    controller =
        new AppController(repository, pipeline, worker, runnable -> handler.post(runnable));
    controller.load();
  }

  public AppController controller() {
    return controller;
  }

  public TranscriptRepository repository() {
    return repository;
  }

  public ExecutorService worker() {
    return worker;
  }
}
