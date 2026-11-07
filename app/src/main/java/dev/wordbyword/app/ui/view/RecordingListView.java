package dev.wordbyword.app.ui.view;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.AppState;
import java.util.Set;
import java.util.function.Consumer;

public final class RecordingListView {
  private final LinearLayout items;
  private final AppController controller;

  public RecordingListView(LinearLayout items, AppController controller) {
    this.items = items;
    this.controller = controller;
  }

  public void render(
      AppState.Item item,
      AppState state,
      boolean selecting,
      Set<String> selectedIds,
      Consumer<String> toggleSelection,
      Consumer<String> beginSelection) {
    View card =
        LayoutInflater.from(items.getContext()).inflate(R.layout.item_recording, items, false);
    ((TextView) card.findViewById(R.id.recording_kind)).setText(R.string.imported_badge);
    ((TextView) card.findViewById(R.id.recording_title)).setText(item.recording().title());
    ((TextView) card.findViewById(R.id.recording_detail)).setText(item.detail());
    TextView status = card.findViewById(R.id.recording_status);
    status.setTextColor(items.getContext().getColor(R.color.accent_text));
    status.setText(
        switch (item.status()) {
          case PROCESSING -> R.string.processing_status;
          case READY -> R.string.ready_status;
          case ERROR -> R.string.error_status;
          case CANCELLED -> R.string.cancelled_status;
          case IMPORTED -> R.string.imported_status;
        });
    if (item.status() == AppState.Status.ERROR) {
      status.setTextColor(items.getContext().getColor(R.color.error));
      android.graphics.drawable.GradientDrawable errorBackground = new android.graphics.drawable.GradientDrawable();
      errorBackground.setColor(0xFFFCE9E5);
      errorBackground.setCornerRadius(16 * items.getResources().getDisplayMetrics().density);
      status.setBackground(errorBackground);
    }
    card.setOnLongClickListener(view -> {
      beginSelection.accept(item.recording().id());
      return true;
    });
    card.setBackgroundResource(selectedIds.contains(item.recording().id()) ? R.drawable.selected_card : R.drawable.card);
    CheckBox selected = card.findViewById(R.id.recording_selected);
    selected.setVisibility(selecting ? View.VISIBLE : View.GONE);
    selected.setChecked(selectedIds.contains(item.recording().id()));
    selected.setOnClickListener(view -> toggleSelection.accept(item.recording().id()));
    if (selecting) {
      card.setOnClickListener(view -> toggleSelection.accept(item.recording().id()));
    }
    ProgressBar progress = card.findViewById(R.id.recording_progress);
    boolean processing = item.status() == AppState.Status.PROCESSING;
    progress.setVisibility(processing ? View.VISIBLE : View.GONE);
    progress.setIndeterminate(item.progress().isEmpty());
    item.progress()
        .ifPresent(
            value -> progress.setProgress((int) Math.round(Math.max(0, Math.min(1, value)) * 100)));
    Button action = card.findViewById(R.id.recording_action);
    action.setVisibility(selecting || item.status() == AppState.Status.READY ? View.GONE : View.VISIBLE);
    card.findViewById(R.id.recording_chevron).setVisibility(!selecting && item.status() == AppState.Status.READY ? View.VISIBLE : View.GONE);
    TextView metadata = card.findViewById(R.id.recording_kind);
    metadata.setVisibility(item.status() == AppState.Status.READY ? View.VISIBLE : View.GONE);
    if (item.transcript() != null) {
      long seconds = item.recording().durationMs().orElse(0) / 1000;
      metadata.setText(String.format(java.util.Locale.ROOT, "%02d:%02d · %s", seconds / 60, seconds % 60,
          items.getContext().getString(R.string.recording_summary, item.transcript().utterances().size(), item.transcript().speakers().size())));
    }
    card.findViewById(R.id.recording_detail).setVisibility(item.status() == AppState.Status.READY || item.status() == AppState.Status.IMPORTED ? View.GONE : View.VISIBLE);
    if (processing) {
      action.setText(state.cancelling() ? R.string.cancelling : R.string.cancel);
      action.setEnabled(!state.cancelling());
      action.setOnClickListener(view -> controller.cancel());
    } else if (item.status() == AppState.Status.READY) {
      action.setText(R.string.open_transcript);
      action.setOnClickListener(view -> controller.select(item.recording().id()));
      if (!selecting) card.setOnClickListener(view -> controller.select(item.recording().id()));
    } else {
      action.setText(
          item.status() == AppState.Status.IMPORTED ? R.string.start_processing : R.string.retry);
      action.setEnabled(!state.busy());
      action.setOnClickListener(view -> controller.start(item.recording().id()));
    }
    items.addView(card);
  }
}
