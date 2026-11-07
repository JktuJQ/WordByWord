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
  private boolean libraryPage;
  private boolean awaitingNewRecording;
  private boolean searching;
  private boolean selecting;

  public MainView(
      Activity activity,
      View root,
      AppController controller,
      Runnable importAudio,
      Runnable recordAudio,
      Runnable exportJson,
      Bundle savedState) {
    this.activity = activity;
    this.root = root;
    this.controller = controller;
    this.importAudio = importAudio;
    this.recordAudio = recordAudio;
    items = root.findViewById(R.id.items);
    if (activity.getResources().getConfiguration().screenHeightDp < 480) {
      root.findViewById(R.id.hero_description).setVisibility(View.GONE);
      View orb = root.findViewById(R.id.hero_orb);
      orb.getLayoutParams().height = Math.round(64 * activity.getResources().getDisplayMetrics().density);
      TextView headline = root.findViewById(R.id.hero_title);
      headline.setText(activity.getString(R.string.home_headline).replace('\n', ' '));
      headline.setTextSize(26);
      root.findViewById(R.id.home_hero).setPadding(0, 0, 0, 0);
      LinearLayout actions = root.findViewById(R.id.library_actions);
      actions.setOrientation(LinearLayout.HORIZONTAL);
      for (int index = 0; index < 2; index++) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
            Math.round(56 * activity.getResources().getDisplayMetrics().density), 1);
        if (index == 1) params.setMarginStart(Math.round(12 * activity.getResources().getDisplayMetrics().density));
        actions.getChildAt(index).setLayoutParams(params);
      }
    }
    recordingView = new RecordingListView(items, controller);
    transcriptView = new TranscriptView(root, items, controller);
    if (savedState != null) {
      searchQuery = savedState.getString("searchQuery", "");
      searching = savedState.getBoolean("searching");
      libraryPage = savedState.getBoolean("libraryPage");
      awaitingNewRecording = savedState.getBoolean("awaitingNewRecording");
      selecting = savedState.getBoolean("selecting");
      var selection = savedState.getStringArrayList("selectedIds");
      if (selection != null) selectedIds.addAll(selection);
    }
    root.findViewById(R.id.back).setOnClickListener(view -> {
      if (lastState != null && lastState.selected() != null) controller.showLibrary();
      else if (selecting) toggleSelecting();
      else if (searching) toggleSearch();
      else controller.showLibrary();
    });
    root.findViewById(R.id.nav_home).setOnClickListener(view -> navigate(false));
    root.findViewById(R.id.nav_library).setOnClickListener(view -> navigate(true));
    root.findViewById(R.id.import_audio).setOnClickListener(view -> importFile());
    root.findViewById(R.id.library_add).setOnClickListener(view -> showSources());
    root.findViewById(R.id.clear_search).setOnClickListener(view -> ((EditText) root.findViewById(R.id.search_input)).setText(""));
    root.findViewById(R.id.select_all).setOnClickListener(view -> selectAll());
    root.findViewById(R.id.record_shortcut).setOnClickListener(view -> {
      awaitingNewRecording = true;
      recordAudio.run();
    });
    root.findViewById(R.id.export_json).setOnClickListener(view -> exportJson.run());
    root.findViewById(R.id.search_toggle).setOnClickListener(view -> toggleSearch());
    root.findViewById(R.id.select_toggle).setOnClickListener(view -> toggleSelecting());
    root.findViewById(R.id.share_selected).setOnClickListener(view -> shareSelected());
    root.findViewById(R.id.delete_selected).setOnClickListener(view -> confirmDelete());
    EditText search = root.findViewById(R.id.search_input);
    search.setImeHintLocales(android.os.LocaleList.forLanguageTags("ru-RU,en-US"));
    search.setText(searchQuery);
    search.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence text, int start, int before, int count) {
            String query = text.toString();
            if (searchQuery.equals(query)) return;
            searchQuery = query;
            if (lastState != null) render(lastState);
          }

          @Override
          public void afterTextChanged(Editable text) {}
        });
  }

  public void saveState(Bundle state) {
    state.putString("searchQuery", searchQuery);
    state.putBoolean("searching", searching);
    state.putBoolean("libraryPage", libraryPage);
    state.putBoolean("awaitingNewRecording", awaitingNewRecording);
    state.putBoolean("selecting", selecting);
    state.putStringArrayList("selectedIds", new java.util.ArrayList<>(selectedIds));
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
    if (libraryPage) {
      navigate(false);
      return true;
    }
    return false;
  }

  public void render(AppState state) {
    if (!state.message().isBlank()) {
      android.widget.Toast.makeText(activity, state.message(), android.widget.Toast.LENGTH_SHORT).show();
      controller.clearMessage();
      return;
    }
    if (awaitingNewRecording && lastState != null && state.items().size() > lastState.items().size()) {
      libraryPage = true;
      awaitingNewRecording = false;
    }
    lastState = state;
    boolean detail = state.selected() != null;
    if (detail) {
      libraryPage = true;
      hideKeyboard();
    }
    boolean home = !detail && !libraryPage;
    String screen = detail ? state.selectedId() : home ? "home" : searching ? "search" : "library";
    if (!screen.equals(renderedScreen)) {
      ((ScrollView) root.findViewById(R.id.content_scroll)).scrollTo(0, 0);
      renderedScreen = screen;
    }
    selectedIds.retainAll(state.items().stream().map(item -> item.recording().id()).collect(java.util.stream.Collectors.toSet()));
    setVisible(R.id.top_toolbar, !home);
    setVisible(R.id.back, detail || searching || selecting);
    setVisible(R.id.home_hero, home);
    setVisible(R.id.page_heading, !home);
    setVisible(R.id.screen_description, detail || !searching && !selecting);
    setVisible(R.id.transcript_playback, detail);
    setVisible(R.id.library_actions, home);
    setVisible(R.id.library_add, !home && !detail && !searching && !selecting);
    setVisible(R.id.selection_actions, !detail && selecting);
    setVisible(R.id.search_toggle, !home && !detail && !selecting && !searching);
    setVisible(R.id.search_row, !detail && searching);
    setVisible(R.id.clear_search, !searchQuery.isEmpty());
    setVisible(R.id.select_toggle, !home && !detail && !searching && !state.items().isEmpty());
    setVisible(R.id.select_all, !detail && selecting);
    setVisible(R.id.export_json, detail);
    setVisible(R.id.empty, !home && !detail && state.items().isEmpty() && !searching);
    setVisible(R.id.items, !home);
    setVisible(R.id.bottom_navigation, !detail && !searching && !selecting);
    updateNavigation(R.id.nav_home, home);
    updateNavigation(R.id.nav_library, !home);
    root.findViewById(R.id.back).setContentDescription(activity.getString(searching ? R.string.close_search : R.string.back_to_library));
    ((TextView) root.findViewById(R.id.select_toggle))
        .setText(selecting ? R.string.stop_selecting : R.string.select_records);
    ((TextView) root.findViewById(R.id.select_all)).setText(
        !state.items().isEmpty() && selectedIds.size() == state.items().size() ? R.string.deselect_all : R.string.select_all);
    root.findViewById(R.id.delete_selected).setEnabled(controller.canDelete(selectedIds));
    root.findViewById(R.id.share_selected).setEnabled(!selectedIds.isEmpty());
    items.removeAllViews();
    if (detail) {
      transcriptView.render(state.selected().transcript());
      setVisible(R.id.no_search_results, false);
      return;
    }
    transcriptView.release();
    text(R.id.screen_title, selecting
        ? activity.getString(R.string.selected_count, selectedIds.size())
        : activity.getString(searching ? R.string.search : R.string.nav_library));
    text(R.id.screen_description, activity.getString(R.string.library_count, state.items().size()));
    if (home) {
      setVisible(R.id.no_search_results, false);
      return;
    }
    int matches = 0;
    for (var item : state.items()) {
      if (item.recording()
          .title()
          .toLowerCase(Locale.ROOT)
          .contains(searchQuery.toLowerCase(Locale.ROOT))) {
        recordingView.render(item, state, selecting, selectedIds, this::toggleSelected, this::beginSelection);
        matches++;
      }
    }
    setVisible(R.id.no_search_results, searching && matches == 0);
  }

  private void updateNavigation(int id, boolean selected) {
    TextView tab = root.findViewById(id);
    tab.setSelected(selected);
    tab.setBackgroundResource(selected ? R.drawable.nav_selected : R.drawable.nav_idle);
    int color = activity.getColor(selected ? R.color.ink : R.color.muted);
    tab.setTextColor(color);
    tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
    tab.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
  }

  private void navigate(boolean toLibrary) {
    hideKeyboard();
    libraryPage = toLibrary;
    searching = false;
    selecting = false;
    selectedIds.clear();
    searchQuery = "";
    ((EditText) root.findViewById(R.id.search_input)).setText("");
    controller.showLibrary();
  }

  private void importFile() {
    awaitingNewRecording = true;
    libraryPage = true;
    if (lastState != null) render(lastState);
    importAudio.run();
  }

  private void toggleSearch() {
    searching = !searching;
    libraryPage = true;
    if (!searching) {
      hideKeyboard();
      searchQuery = "";
      ((EditText) root.findViewById(R.id.search_input)).setText("");
    }
    if (lastState != null) render(lastState);
    if (searching) {
      EditText input = root.findViewById(R.id.search_input);
      input.requestFocus();
      input.post(() -> ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
          .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
    }
  }

  private void hideKeyboard() {
    ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(root.getWindowToken(), 0);
    root.findViewById(R.id.search_input).clearFocus();
  }

  private void toggleSelecting() {
    selecting = !selecting;
    selectedIds.clear();
    if (lastState != null) render(lastState);
  }

  private void beginSelection(String id) {
    if (searching) toggleSearch();
    selecting = true;
    selectedIds.add(id);
    if (lastState != null) render(lastState);
  }

  private void selectAll() {
    if (lastState == null) return;
    if (selectedIds.size() == lastState.items().size()) selectedIds.clear();
    else for (var item : lastState.items()) selectedIds.add(item.recording().id());
    render(lastState);
  }

  private void toggleSelected(String id) {
    if (!selectedIds.add(id)) selectedIds.remove(id);
    if (lastState != null) render(lastState);
  }

  private void showSources() {
    Dialog dialog = new Dialog(activity);
    View sheet = activity.getLayoutInflater().inflate(R.layout.source_sheet, null);
    sheet
        .findViewById(R.id.source_file)
        .setOnClickListener(
            view -> {
              dialog.dismiss();
              importFile();
            });
    sheet
        .findViewById(R.id.source_microphone)
        .setOnClickListener(
            view -> {
              dialog.dismiss();
              awaitingNewRecording = true;
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
    activity.startActivity(
        Intent.createChooser(share, activity.getString(R.string.share_selected)));
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
              controller.delete(Set.copyOf(selectedIds));
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
