package dev.wordbyword.app;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.diarization.SherpaOnnxSpeakerDiarizer;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recognition.SherpaSpeechRecognizer;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.OverlapTranscriptAssembler;
import dev.wordbyword.app.processing.LocalTranscriptionPipeline;
import dev.wordbyword.app.storage.FileTranscriptRepository;
import dev.wordbyword.app.summary.LocalSummarizer;
import dev.wordbyword.app.summary.SummaryRunner;
import dev.wordbyword.app.summary.SummaryStore;
import dev.wordbyword.app.ui.AppController;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WordByWordApplication extends Application {
  private final ExecutorService processing =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "wbw-processing"));
  private final ExecutorService diarization =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "wbw-diarization"));
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "wbw-io"));
  private AppController controller;
  private TranscriptRepository repository;

  @Override
  public void onCreate() {
    super.onCreate();
    TranscriptionPipeline pipeline =
        new LocalTranscriptionPipeline(
            new AndroidAudioPreparer(this),
            new SherpaSpeechRecognizer(getAssets()),
            new SherpaOnnxSpeakerDiarizer(this),
            new OverlapTranscriptAssembler(),
            BuildConfig.PARALLEL_INFERENCE ? diarization : Runnable::run);
    var handler = new Handler(Looper.getMainLooper());
    repository =
        new FileTranscriptRepository(
            getFilesDir().toPath().resolve("library"),
            uri -> getContentResolver().openInputStream(Uri.parse(uri.toString())),
            exception ->
                handler.post(
                    () -> controller.notifyMessage("Некоторые записи повреждены и не загружены")));
    controller =
        new AppController(repository, pipeline, processing, io, runnable -> handler.post(runnable));
    controller.configureSummaries(new SummaryRunner(new LocalSummarizer(this),
        new SummaryStore(getFilesDir().toPath().resolve("library")), processing,
        runnable -> handler.post(runnable)));
    controller.load();
  }

  public AppController controller() {
    return controller;
  }

  public TranscriptRepository repository() {
    return repository;
  }

  public Executor ioExecutor() {
    return io;
  }
}
