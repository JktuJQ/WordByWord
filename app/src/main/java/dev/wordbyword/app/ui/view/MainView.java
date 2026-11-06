package dev.wordbyword.app.ui.view;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.AppState;

public final class MainView {
  private final View root;
  private final LinearLayout items;
  private final RecordingListView recordingView;
  private final TranscriptView transcriptView;
  private String renderedScreen;

  public MainView(
      View root,
      AppController controller,
      Runnable importAudio,
      Runnable exportJson,
      Runnable openDemo) {
    this.root = root;
    items = root.findViewById(R.id.items);
    recordingView = new RecordingListView(items, controller);
    transcriptView = new TranscriptView(root, items);
    root.findViewById(R.id.back).setOnClickListener(view -> controller.showLibrary());
    root.findViewById(R.id.import_audio).setOnClickListener(view -> importAudio.run());
    root.findViewById(R.id.demo).setOnClickListener(view -> openDemo.run());
    root.findViewById(R.id.export_json).setOnClickListener(view -> exportJson.run());
  }

  public void render(AppState state) {
    boolean detail = state.selected() != null;
    String screen = detail ? state.selectedId() : "library";
    if (!screen.equals(renderedScreen)) {
      ((ScrollView) root.findViewById(R.id.content_scroll)).scrollTo(0, 0);
      renderedScreen = screen;
    }
    setVisible(R.id.back, detail);
    setVisible(R.id.import_audio, !detail);
    setVisible(R.id.demo, !detail);
    setVisible(R.id.export_json, detail);
    setVisible(R.id.empty, !detail && state.items().isEmpty());
    root.findViewById(R.id.demo).setEnabled(!state.busy());
    text(R.id.message, state.message());
    setVisible(R.id.message, !state.message().isBlank());
    items.removeAllViews();
    if (detail) {
      transcriptView.render(state.selected().transcript());
    } else {
      text(R.id.screen_title, root.getContext().getString(R.string.library));
      text(R.id.screen_description, root.getContext().getString(R.string.library_description));
      for (var item : state.items()) {
        recordingView.render(item, state);
      }
    }
  }

  private void setVisible(int id, boolean visible) {
    root.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void text(int id, String value) {
    ((TextView) root.findViewById(id)).setText(value);
  }
}
