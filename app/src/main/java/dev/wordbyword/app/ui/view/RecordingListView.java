package dev.wordbyword.app.ui.view;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.AppState;

public final class RecordingListView {
  private final LinearLayout items;
  private final AppController controller;

  public RecordingListView(LinearLayout items, AppController controller) {
    this.items = items;
    this.controller = controller;
  }

  public void render(AppState.Item item, AppState state) {
    View card =
        LayoutInflater.from(items.getContext()).inflate(R.layout.item_recording, items, false);
    boolean demo = "demo".equals(item.recording().audioUri().getScheme());
    ((TextView) card.findViewById(R.id.recording_kind))
        .setText(
            demo
                ? items.getContext().getString(R.string.demo_badge, "00:18")
                : items.getContext().getString(R.string.imported_badge));
    ((TextView) card.findViewById(R.id.recording_title)).setText(item.recording().title());
    ((TextView) card.findViewById(R.id.recording_detail)).setText(item.detail());
    ProgressBar progress = card.findViewById(R.id.recording_progress);
    boolean processing = item.status() == AppState.Status.PROCESSING;
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
    } else if (item.status() == AppState.Status.READY) {
      action.setText(R.string.open_transcript);
      action.setOnClickListener(view -> controller.select(item.recording().id()));
    } else {
      action.setText(
          item.status() == AppState.Status.IMPORTED
              ? (demo ? R.string.prepare_demo : R.string.start_processing)
              : R.string.retry);
      action.setEnabled(!state.busy());
      action.setOnClickListener(view -> controller.start(item.recording().id()));
    }
    items.addView(card);
  }
}
