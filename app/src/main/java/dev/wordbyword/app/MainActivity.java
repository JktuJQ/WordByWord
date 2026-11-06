package dev.wordbyword.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.demo.DemoBackend;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.AudioDocuments;
import java.util.Locale;

public final class MainActivity extends Activity {
  private static final int PICK_AUDIO = 100;
  private static final int CREATE_JSON = 101;
  private AppController controller;
  private AudioDocuments documents;
  private String pendingExportId;
  private String renderedScreen;
  private LinearLayout items;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    var application = (WordByWordApplication) getApplication();
    controller = application.controller();
    documents = new AudioDocuments(application);
    if (savedInstanceState != null) {
      pendingExportId = savedInstanceState.getString("exportId");
    }
    setContentView(R.layout.activity_main);
    items = findViewById(R.id.items);
    findViewById(R.id.root)
        .setOnApplyWindowInsetsListener(
            (view, insets) -> {
              if (Build.VERSION.SDK_INT >= 30) {
                var bars =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
              } else {
                view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
              }
              return insets;
            });
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
    }
    findViewById(R.id.back).setOnClickListener(view -> controller.showLibrary());
    findViewById(R.id.import_audio).setOnClickListener(view -> pickAudio());
    findViewById(R.id.demo)
        .setOnClickListener(
            view -> {
              var recording = DemoBackend.recording();
              controller.add(recording);
              var item =
                  controller.state().items().stream()
                      .filter(value -> value.recording().id().equals(recording.id()))
                      .findFirst()
                      .orElseThrow();
              if (item.status() == AppController.Status.READY) {
                controller.select(recording.id());
              } else {
                controller.start(recording.id());
              }
            });
    findViewById(R.id.export_json).setOnClickListener(view -> pickExportFile());
  }

  @Override
  protected void onStart() {
    super.onStart();
    controller.attach(this::render);
  }

  @Override
  protected void onStop() {
    controller.detach();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("exportId", pendingExportId);
    super.onSaveInstanceState(state);
  }

  @Override
  @SuppressLint("GestureBackNavigation")
  public void onBackPressed() {
    goBack();
  }

  private void goBack() {
    if (controller.state().selectedId() != null) {
      controller.showLibrary();
    } else {
      finish();
    }
  }

  private void render(AppController.State state) {
    boolean detail = state.selected() != null;
    String screen = detail ? state.selectedId() : "library";
    if (!screen.equals(renderedScreen)) {
      ((ScrollView) findViewById(R.id.content_scroll)).scrollTo(0, 0);
      renderedScreen = screen;
    }
    setVisible(R.id.back, detail);
    setVisible(R.id.import_audio, !detail);
    setVisible(R.id.demo, !detail);
    setVisible(R.id.export_json, detail);
    setVisible(R.id.empty, !detail && state.items().isEmpty());
    findViewById(R.id.demo).setEnabled(!state.busy());
    text(R.id.message, state.message());
    setVisible(R.id.message, !state.message().isBlank());
    items.removeAllViews();
    if (detail) {
      renderTranscript(state.selected().transcript());
    } else {
      text(R.id.screen_title, getString(R.string.library));
      text(R.id.screen_description, getString(R.string.library_description));
      for (var item : state.items()) {
        renderRecording(item, state);
      }
    }
  }

  private void renderRecording(AppController.Item item, AppController.State state) {
    View card = getLayoutInflater().inflate(R.layout.item_recording, items, false);
    boolean demo = "demo".equals(item.recording().audioUri().getScheme());
    ((TextView) card.findViewById(R.id.recording_kind))
        .setText(
            demo ? getString(R.string.demo_badge, "00:18") : getString(R.string.imported_badge));
    ((TextView) card.findViewById(R.id.recording_title)).setText(item.recording().title());
    ((TextView) card.findViewById(R.id.recording_detail)).setText(item.detail());
    ProgressBar progress = card.findViewById(R.id.recording_progress);
    boolean processing = item.status() == AppController.Status.PROCESSING;
    progress.setVisibility(processing ? View.VISIBLE : View.GONE);
    progress.setIndeterminate(item.progress().isEmpty());
    item.progress()
        .ifPresent(
            value -> progress.setProgress((int) Math.round(Math.max(0, Math.min(1, value)) * 100)));
    Button action = card.findViewById(R.id.recording_action);
    if (processing) {
      action.setText(state.cancelling() ? R.string.cancelling : R.string.cancel);
      action.setEnabled(!state.cancelling());
      action.setOnClickListener(view -> controller.cancel());
    } else if (item.status() == AppController.Status.READY) {
      action.setText(R.string.open_transcript);
      action.setOnClickListener(view -> controller.select(item.recording().id()));
    } else {
      action.setText(
          item.status() == AppController.Status.IMPORTED
              ? (demo ? R.string.prepare_demo : R.string.start_processing)
              : R.string.retry);
      action.setEnabled(!state.busy());
      action.setOnClickListener(view -> controller.start(item.recording().id()));
    }
    items.addView(card);
  }

  private void renderTranscript(Transcript transcript) {
    text(R.id.screen_title, transcript.recording().title());
    text(
        R.id.screen_description,
        "demo".equals(transcript.recording().audioUri().getScheme())
            ? getString(R.string.demo_transcript)
            : getString(
                R.string.recording_summary,
                transcript.utterances().size(),
                transcript.speakers().size()));
    if (transcript.utterances().isEmpty()) {
      var empty = new TextView(this);
      empty.setText(R.string.transcript_empty);
      items.addView(empty);
    }
    for (var utterance : transcript.utterances()) {
      View card = getLayoutInflater().inflate(R.layout.item_utterance, items, false);
      String name =
          transcript.speakers().stream()
              .filter(speaker -> utterance.speakerId().filter(speaker.id()::equals).isPresent())
              .map(Transcript.Speaker::displayName)
              .findFirst()
              .orElse(getString(R.string.unknown_speaker));
      TextView speaker = card.findViewById(R.id.speaker);
      speaker.setText(name);
      int index =
          utterance
              .speakerId()
              .map(
                  id ->
                      transcript.speakers().stream()
                          .map(Transcript.Speaker::id)
                          .collect(java.util.stream.Collectors.toList())
                          .indexOf(id))
              .orElse(0);
      speaker.setTextColor(getColor(index % 2 == 0 ? R.color.accent : R.color.secondary_speaker));
      ((TextView) card.findViewById(R.id.timing))
          .setText(
              getString(R.string.time_range, time(utterance.startMs()), time(utterance.endMs())));
      ((TextView) card.findViewById(R.id.utterance_text)).setText(utterance.text());
      items.addView(card);
    }
  }

  private static String time(long milliseconds) {
    long seconds = milliseconds / 1000;
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private void setVisible(int id, boolean visible) {
    findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void text(int id, String value) {
    ((TextView) findViewById(id)).setText(value);
  }

  private void pickAudio() {
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .setType("audio/*")
              .addCategory(Intent.CATEGORY_OPENABLE),
          PICK_AUDIO);
    } catch (ActivityNotFoundException exception) {
      controller.notifyMessage(getString(R.string.picker_unavailable));
    }
  }

  private void pickExportFile() {
    var selected = controller.state().selected();
    if (selected == null || selected.transcript() == null) {
      return;
    }
    pendingExportId = selected.recording().id();
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .setType("application/json")
              .addCategory(Intent.CATEGORY_OPENABLE)
              .putExtra(Intent.EXTRA_TITLE, "transcript-" + pendingExportId + ".json"),
          CREATE_JSON);
    } catch (ActivityNotFoundException exception) {
      pendingExportId = null;
      controller.notifyMessage(getString(R.string.picker_unavailable));
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null || data.getData() == null) {
      if (requestCode == CREATE_JSON) {
        pendingExportId = null;
        controller.notifyMessage(getString(R.string.export_cancelled));
      }
      return;
    }
    if (requestCode == PICK_AUDIO) {
      documents.importAudio(data.getData());
    } else if (requestCode == CREATE_JSON) {
      String id = pendingExportId;
      pendingExportId = null;
      var selected =
          controller.state().items().stream()
              .filter(item -> item.recording().id().equals(id))
              .findFirst();
      if (selected.isEmpty() || selected.get().transcript() == null) {
        controller.notifyMessage(getString(R.string.export_unavailable));
      } else {
        documents.exportJson(data.getData(), selected.get().transcript());
      }
    }
  }
}
