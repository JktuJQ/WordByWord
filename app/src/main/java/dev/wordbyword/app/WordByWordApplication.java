package dev.wordbyword.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import dev.wordbyword.app.demo.DemoBackend;
import dev.wordbyword.app.ui.AppController;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WordByWordApplication extends Application {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AppController controller;

  @Override
  public void onCreate() {
    super.onCreate();
    var backend = new DemoBackend();
    var handler = new Handler(Looper.getMainLooper());
    controller = new AppController(backend, backend, worker, runnable -> handler.post(runnable));
  }

  public AppController controller() {
    return controller;
  }

  public ExecutorService worker() {
    return worker;
  }
}
