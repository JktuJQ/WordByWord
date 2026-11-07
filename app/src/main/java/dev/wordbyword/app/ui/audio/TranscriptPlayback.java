package dev.wordbyword.app.ui.audio;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import dev.wordbyword.app.core.recording.Recording;
import java.util.function.Consumer;

public final class TranscriptPlayback {
  public record State(int positionMs, int durationMs, boolean playing) {}

  private final Context context;
  private final Consumer<State> listener;
  private final Runnable failure;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable ticker = this::publish;
  private MediaPlayer player;
  private Recording recording;

  public TranscriptPlayback(Context context, Consumer<State> listener, Runnable failure) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    this.failure = failure;
  }

  public void showRecording(Recording recording) {
    if (this.recording != null && !this.recording.equals(recording)) release();
    this.recording = recording;
  }

  public void toggle(Recording recording) {
    execute(
        recording,
        () -> {
          if (player.isPlaying()) {
            player.pause();
          } else {
            if (player.getCurrentPosition() >= player.getDuration()) seekTo(0);
            player.start();
          }
        });
  }

  public void playAt(Recording recording, long startMs) {
    execute(
        recording,
        () -> {
          seekTo(startMs);
          player.start();
        });
  }

  public void seek(Recording recording, long offsetMs) {
    execute(recording, () -> seekTo(player.getCurrentPosition() + offsetMs));
  }

  public void seekTo(Recording recording, long targetMs) {
    execute(recording, () -> seekTo(targetMs));
  }

  public void refresh() {
    publish();
  }

  public void release() {
    handler.removeCallbacks(ticker);
    if (player != null) {
      player.release();
      player = null;
    }
    recording = null;
  }

  private void seekTo(long targetMs) {
    long bounded = Math.max(0, Math.min(player.getDuration(), targetMs));
    player.seekTo(bounded, MediaPlayer.SEEK_CLOSEST);
  }

  private void execute(Recording recording, Runnable action) {
    try {
      showRecording(recording);
      if (player == null) {
        player = MediaPlayer.create(context, Uri.parse(recording.audioUri().toString()));
        if (player == null) throw new IllegalStateException("Cannot open recording audio");
        player.setOnCompletionListener(finished -> publish());
        player.setOnSeekCompleteListener(finished -> publish());
        player.setOnErrorListener(
            (failed, what, extra) -> {
              handleFailure(new IllegalStateException("MediaPlayer error " + what + ": " + extra));
              return true;
            });
      }
      action.run();
    } catch (RuntimeException exception) {
      handleFailure(exception);
      return;
    }
    publish();
  }

  private void publish() {
    State state;
    try {
      int duration =
          player != null
              ? player.getDuration()
              : recording == null
                  ? 0
                  : (int) Math.min(Integer.MAX_VALUE, recording.durationMs().orElse(0));
      duration = Math.max(0, duration);
      int position =
          player == null ? 0 : Math.max(0, Math.min(player.getCurrentPosition(), duration));
      state = new State(position, duration, player != null && player.isPlaying());
    } catch (RuntimeException exception) {
      handleFailure(exception);
      return;
    }
    listener.accept(state);
    handler.removeCallbacks(ticker);
    if (state.playing()) handler.postDelayed(ticker, 200);
  }

  private void handleFailure(RuntimeException exception) {
    Log.w(
        "TranscriptPlayback",
        "Cannot play recording " + (recording == null ? "" : recording.id()),
        exception);
    release();
    listener.accept(new State(0, 0, false));
    failure.run();
  }
}
