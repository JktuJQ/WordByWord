package dev.wordbyword.app.ui.audio;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import dev.wordbyword.app.core.recording.Recording;

public final class TranscriptPlayback {
  private final Context context;
  private final Runnable failure;
  private MediaPlayer player;
  private Recording recording;
  private long playingStart = -1;

  public TranscriptPlayback(Context context, Runnable failure) {
    this.context = context.getApplicationContext();
    this.failure = failure;
  }

  public void showRecording(Recording recording) {
    if (this.recording != null && !this.recording.equals(recording)) release();
    this.recording = recording;
  }

  public void playAt(Recording recording, long startMs) {
    execute(
        recording,
        () -> {
          if (player.isPlaying() && playingStart == startMs) {
            player.pause();
          } else {
            player.seekTo((int) Math.max(0, Math.min(startMs, Integer.MAX_VALUE)));
            player.start();
            playingStart = startMs;
          }
        });
  }

  public void seek(Recording recording, long startMs, long offsetMs) {
    execute(
        recording,
        () -> {
          long current = player.isPlaying() ? player.getCurrentPosition() : startMs;
          long target = Math.max(0, Math.min(player.getDuration(), current + offsetMs));
          player.seekTo((int) target);
        });
  }

  public void release() {
    if (player != null) {
      player.release();
      player = null;
    }
    recording = null;
    playingStart = -1;
  }

  private void execute(Recording recording, Runnable action) {
    try {
      showRecording(recording);
      if (player == null) {
        player = MediaPlayer.create(context, Uri.parse(recording.audioUri().toString()));
        if (player == null) throw new IllegalStateException("Cannot open recording audio");
      }
      action.run();
    } catch (RuntimeException exception) {
      Log.w("TranscriptPlayback", "Cannot play recording " + recording.id(), exception);
      release();
      failure.run();
    }
  }
}
