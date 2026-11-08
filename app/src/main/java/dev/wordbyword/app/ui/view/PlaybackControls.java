package dev.wordbyword.app.ui.view;

import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.audio.TranscriptPlayback;
import java.util.Locale;
import java.util.function.LongConsumer;

public final class PlaybackControls {
  private final TextView position;
  private final ImageButton toggle;
  private final SeekBar seekbar;

  public PlaybackControls(View root, Runnable play, LongConsumer seek, LongConsumer seekTo) {
    position = root.findViewById(R.id.playback_position);
    toggle = root.findViewById(R.id.playback_toggle);
    seekbar = root.findViewById(R.id.playback_seekbar);
    toggle.setOnClickListener(view -> play.run());
    root.findViewById(R.id.playback_back).setOnClickListener(view -> seek.accept(-5000));
    root.findViewById(R.id.playback_forward).setOnClickListener(view -> seek.accept(5000));
    seekbar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (fromUser) seekTo.accept(value);
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {}
        });
  }

  public void render(TranscriptPlayback.State state) {
    int current = state.positionMs();
    int duration = state.durationMs();
    position.setText(
        String.format(
            Locale.ROOT,
            "%02d:%02d / %02d:%02d",
            current / 60000,
            current / 1000 % 60,
            duration / 60000,
            duration / 1000 % 60));
    seekbar.setMax(Math.max(1, duration));
    seekbar.setProgress(Math.min(current, duration));
    toggle.setImageResource(state.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
    toggle.setContentDescription(
        toggle
            .getContext()
            .getString(state.playing() ? R.string.pause_utterance : R.string.play_utterance));
  }
}
