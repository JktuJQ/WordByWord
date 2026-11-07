package dev.wordbyword.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.window.OnBackInvokedDispatcher;
import dev.wordbyword.app.demo.DemoBackend;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.document.AudioDocuments;
import dev.wordbyword.app.ui.document.DocumentPicker;
import dev.wordbyword.app.ui.view.MainView;
import dev.wordbyword.app.ui.view.WindowInsetsHandler;

public final class MainActivity extends Activity {
  private AppController controller;
  private DocumentPicker documents;
  private MainView screen;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    var application = (WordByWordApplication) getApplication();
    controller = application.controller();
    documents =
        new DocumentPicker(this, controller, new AudioDocuments(application), savedInstanceState);
    setContentView(R.layout.activity_main);
    var root = findViewById(R.id.root);
    screen =
        new MainView(
            root,
            controller,
            documents::pickAudio,
            documents::pickExportFile,
            () -> controller.open(DemoBackend.recording()));
    WindowInsetsHandler.apply(root);
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    controller.attach(screen::render);
  }

  @Override
  protected void onStop() {
    controller.detach();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    documents.saveState(state);
    super.onSaveInstanceState(state);
  }

  @Override
  @SuppressLint("GestureBackNavigation")
  @Deprecated
  public void onBackPressed() {
    goBack();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    documents.onActivityResult(requestCode, resultCode, data);
  }

  private void goBack() {
    if (controller.state().selectedId() != null) controller.showLibrary();
    else finish();
  }
}
