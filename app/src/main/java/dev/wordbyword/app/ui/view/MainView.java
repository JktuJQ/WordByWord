package dev.wordbyword.app.ui.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.AppState;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MainView {
  private final View root;
  private final Activity activity;
  private final AppController controller;
  private final LinearLayout items;
  private final RecordingListView recordingView;
  private final TranscriptView transcriptView;
  private final Runnable importAudio;
  private final Runnable recordAudio;
  private final Set<String> selectedIds = new LinkedHashSet<>();
  private AppState lastState;
  private String renderedScreen;
  private String searchQuery = "";
  private boolean searching;
  private boolean selecting;

  public MainView(
      Activity activity,
      View root,
      AppController controller,
      Runnable importAudio,
      Runnable recordAudio,
      Runnable exportJson,
      Runnable openDemo,
      Bundle savedState) {
    this.activity = activity;
    this.root = root;
    this.controller = controller;
    this.importAudio = importAudio;
    this.recordAudio = recordAudio;
    items = root.findViewById(R.id.items);
    recordingView = new RecordingListView(items, controller);
    transcriptView = new TranscriptView(root, items, controller);
    if (savedState != null) {
      searchQuery = savedState.getString("searchQuery", "");
      searching = savedState.getBoolean("searching");
    }
    root.findViewById(R.id.back).setOnClickListener(view -> controller.showLibrary());
    root.findViewById(R.id.import_audio).setOnClickListener(view -> showSources());
    root.findViewById(R.id.demo).setOnClickListener(view -> openDemo.run());
    root.findViewById(R.id.record_shortcut).setOnClickListener(view -> recordAudio.run());
    root.findViewById(R.id.export_json).setOnClickListener(view -> exportJson.run());
    root.findViewById(R.id.search_toggle).setOnClickListener(view -> toggleSearch());
    root.findViewById(R.id.select_toggle).setOnClickListener(view -> toggleSelecting());
    root.findViewById(R.id.share_selected).setOnClickListener(view -> shareSelected());
    root.findViewById(R.id.delete_selected).setOnClickListener(view -> confirmDelete());
    EditText search = root.findViewById(R.id.search_input);
    search.setText(searchQuery);
    search.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence text, int start, int before, int count) {
            searchQuery = text.toString();
            if (lastState != null) render(lastState);
          }

          @Override
          public void afterTextChanged(Editable text) {}
        });
  }

  public void saveState(Bundle state) {
    state.putString("searchQuery", searchQuery);
    state.putBoolean("searching", searching);
  }

  public void release() {
    transcriptView.release();
  }

  public boolean handleBack() {
    if (selecting) {
      toggleSelecting();
      return true;
    }
    if (searching) {
      toggleSearch();
      return true;
    }
    return false;
  }

  public void render(AppState state) {
    lastState = state;
    boolean detail = state.selected() != null;
    String screen = detail ? state.selectedId() : "library";
    if (!screen.equals(renderedScreen)) {
      ((ScrollView) root.findViewById(R.id.content_scroll)).scrollTo(0, 0);
      renderedScreen = screen;
    }
    setVisible(R.id.back, detail);
    setVisible(R.id.library_actions, !detail && !searching && !selecting);
    setVisible(R.id.selection_actions, !detail && selecting);
    setVisible(R.id.search_toggle, !detail && !selecting);
    setVisible(R.id.search_input, !detail && searching);
    setVisible(R.id.select_toggle, !detail && !searching && !state.items().isEmpty());
    setVisible(R.id.export_json, detail);
    setVisible(R.id.empty, !detail && state.items().isEmpty() && !searching);
    ((TextView) root.findViewById(R.id.select_toggle))
        .setText(selecting ? R.string.stop_selecting : R.string.select_records);
    ((TextView) root.findViewById(R.id.selected_count))
        .setText(activity.getString(R.string.selected_count, selectedIds.size()));
    root.findViewById(R.id.demo).setEnabled(!state.busy());
    root.findViewById(R.id.delete_selected).setEnabled(!state.busy() && !selectedIds.isEmpty());
    root.findViewById(R.id.share_selected).setEnabled(!selectedIds.isEmpty());
    text(R.id.message, state.message());
    setVisible(R.id.message, !state.message().isBlank());
    items.removeAllViews();
    if (detail) {
      transcriptView.render(state.selected().transcript());
      setVisible(R.id.no_search_results, false);
      return;
    }
    transcriptView.release();
    text(R.id.screen_title, activity.getString(R.string.library));
    text(
        R.id.screen_description,
        searching ? activity.getString(R.string.search) : activity.getString(R.string.library_description));
    int matches = 0;
    for (var item : state.items()) {
      if (item.recording().title().toLowerCase(Locale.ROOT)
          .contains(searchQuery.toLowerCase(Locale.ROOT))) {
        recordingView.render(item, state, selecting, selectedIds, this::toggleSelected);
        matches++;
      }
    }
    setVisible(R.id.no_search_results, searching && matches == 0);
  }

  private void toggleSearch() {
    searching = !searching;
    if (!searching) {
      searchQuery = "";
      ((EditText) root.findViewById(R.id.search_input)).setText("");
    }
    if (lastState != null) render(lastState);
    if (searching) root.findViewById(R.id.search_input).requestFocus();
  }

  private void toggleSelecting() {
    selecting = !selecting;
    selectedIds.clear();
    if (lastState != null) render(lastState);
  }

  private void toggleSelected(String id) {
    if (!selectedIds.add(id)) selectedIds.remove(id);
    if (lastState != null) render(lastState);
  }

  private void showSources() {
    Dialog dialog = new Dialog(activity);
    View sheet = activity.getLayoutInflater().inflate(R.layout.source_sheet, null);
    sheet.findViewById(R.id.source_file)
        .setOnClickListener(
            view -> {
              dialog.dismiss();
              importAudio.run();
            });
    sheet.findViewById(R.id.source_microphone)
        .setOnClickListener(
            view -> {
              dialog.dismiss();
              recordAudio.run();
            });
    dialog.setContentView(sheet);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      window.setGravity(Gravity.BOTTOM);
    }
    dialog.show();
    if (window != null) {
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
  }

  private void shareSelected() {
    StringBuilder text = new StringBuilder();
    for (var item : lastState.items()) {
      if (selectedIds.contains(item.recording().id())) {
        if (text.length() > 0) text.append('\n');
        text.append(item.recording().title());
      }
    }
    if (text.length() == 0) return;
    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("text/plain");
    share.putExtra(Intent.EXTRA_TEXT, text.toString());
    activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share_selected)));
  }

  private void confirmDelete() {
    if (selectedIds.isEmpty()) return;
    new AlertDialog.Builder(activity)
        .setTitle(R.string.delete_confirm_title)
        .setMessage(R.string.delete_confirm_message)
        .setNegativeButton(R.string.not_now, null)
        .setPositiveButton(
            R.string.delete_selected,
            (dialog, which) -> {
              controller.removeVisible(Set.copyOf(selectedIds));
              toggleSelecting();
            })
        .show();
  }

  private void setVisible(int id, boolean visible) {
    root.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void text(int id, String value) {
    ((TextView) root.findViewById(id)).setText(value);
  }
}
