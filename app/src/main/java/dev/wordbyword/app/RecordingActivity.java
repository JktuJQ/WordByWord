package dev.wordbyword.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import dev.wordbyword.app.ui.view.WindowInsetsHandler;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public final class RecordingActivity extends Activity {
  private static final int MICROPHONE_PERMISSION = 201;

  private enum State {
    READY,
    RECORDING,
    PAUSED,
    COMPLETE
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private State state = State.READY;
  private MediaRecorder recorder;
  private dev.wordbyword.app.ui.audio.TranscriptPlayback preview;
  private dev.wordbyword.app.core.recording.Recording previewRecording;
  private File audioFile;
  private long elapsedMs;
  private long startedAt;
  private float orbScale = 1f;
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (state == State.RECORDING) {
            renderTimer();
            animateOrb();
            handler.postDelayed(this, 60);
          }
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_recording);
    WindowInsetsHandler.apply(findViewById(R.id.recording_root));
    findViewById(R.id.recording_back).setOnClickListener(view -> leave());
    findViewById(R.id.recording_start).setOnClickListener(view -> begin());
    findViewById(R.id.recording_pause).setOnClickListener(view -> togglePause());
    findViewById(R.id.recording_finish).setOnClickListener(view -> finishRecording());
    findViewById(R.id.recording_cancel).setOnClickListener(view -> confirmDiscard());
    preview = new dev.wordbyword.app.ui.audio.TranscriptPlayback(this, progress -> {
      android.widget.SeekBar seek = findViewById(R.id.playback_seekbar);
      seek.setMax(Math.max(1, progress.durationMs()));
      seek.setProgress(progress.positionMs());
      ((TextView) findViewById(R.id.playback_position)).setText(String.format(Locale.ROOT,
          "%02d:%02d / %02d:%02d", progress.positionMs() / 60000, progress.positionMs() / 1000 % 60,
          progress.durationMs() / 60000, progress.durationMs() / 1000 % 60));
      ((android.widget.ImageButton) findViewById(R.id.playback_toggle)).setImageResource(
          progress.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
      findViewById(R.id.playback_toggle).setContentDescription(getString(
          progress.playing() ? R.string.pause_utterance : R.string.play_utterance));
    }, this::showFailure);
    findViewById(R.id.playback_toggle).setOnClickListener(view -> {
      if (previewRecording != null) preview.toggle(previewRecording);
    });
    findViewById(R.id.playback_back).setOnClickListener(view -> {
      if (previewRecording != null) preview.seek(previewRecording, -5000);
    });
    findViewById(R.id.playback_forward).setOnClickListener(view -> {
      if (previewRecording != null) preview.seek(previewRecording, 5000);
    });
    ((android.widget.SeekBar) findViewById(R.id.playback_seekbar)).setOnSeekBarChangeListener(
        new android.widget.SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
            if (fromUser && previewRecording != null) preview.seekTo(previewRecording, value);
          }
          public void onStartTrackingTouch(android.widget.SeekBar bar) {}
          public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
    findViewById(R.id.recording_transcribe).setOnClickListener(view -> sendRecording());
    findViewById(R.id.recording_delete).setOnClickListener(view -> confirmDiscard());
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);
    }
    render();
  }

  @Override
  protected void onStop() {
    if (state == State.RECORDING) togglePause();
    if (preview != null) {
      preview.release();

    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(ticker);
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (RuntimeException exception) {
        Log.w("RecordingActivity", "Cannot stop recording", exception);
        if (audioFile != null) audioFile.delete();
      }
      recorder.release();
      recorder = null;
    }
    if (preview != null) preview.release();
    super.onDestroy();
  }

  @Override
  @SuppressLint("GestureBackNavigation")
  @Deprecated
  public void onBackPressed() {
    leave();
  }

  private void begin() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
      return;
    }
    try {
      File directory = new File(getFilesDir(), "recordings");
      if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
      audioFile = new File(directory, UUID.randomUUID() + ".m4a");
      @SuppressWarnings("deprecation")
      MediaRecorder newRecorder =
          Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
      recorder = newRecorder;
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setOutputFile(audioFile.getAbsolutePath());
      recorder.prepare();
      recorder.start();
      elapsedMs = 0;
      startedAt = SystemClock.elapsedRealtime();
      state = State.RECORDING;
      handler.post(ticker);
      render();
    } catch (IOException | RuntimeException exception) {
      Log.w("RecordingActivity", "Cannot start recording", exception);
      releaseFailedRecorder();
      showFailure();
    }
  }

  private void togglePause() {
    if (recorder == null) return;
    try {
      if (state == State.RECORDING) {
        recorder.pause();
        elapsedMs += SystemClock.elapsedRealtime() - startedAt;
        state = State.PAUSED;
        handler.removeCallbacks(ticker);
        resetOrb();
      } else if (state == State.PAUSED) {
        recorder.resume();
        startedAt = SystemClock.elapsedRealtime();
        state = State.RECORDING;
        handler.post(ticker);
      }
      render();
    } catch (RuntimeException exception) {
      Log.w("RecordingActivity", "Cannot pause or resume recording", exception);
      finishRecording();
    }
  }

  private void finishRecording() {
    if (recorder == null || (state != State.RECORDING && state != State.PAUSED)) return;
    if (state == State.RECORDING) elapsedMs += SystemClock.elapsedRealtime() - startedAt;
    handler.removeCallbacks(ticker);
    resetOrb();
    try {
      recorder.stop();
      state = State.COMPLETE;
      previewRecording = new dev.wordbyword.app.core.recording.Recording("preview",
          audioFile.toURI(), "Запись", java.util.OptionalLong.of(elapsedMs));
      preview.showRecording(previewRecording);
      preview.refresh();
    } catch (RuntimeException exception) {
      Log.w("RecordingActivity", "Cannot finish recording", exception);
      if (audioFile != null) audioFile.delete();
      audioFile = null;
      state = State.READY;
      showFailure();
    } finally {
      recorder.release();
      recorder = null;
      render();
    }
  }

  private void sendRecording() {
    if (audioFile == null) return;
    String title = ((EditText) findViewById(R.id.recording_name)).getText().toString().trim();
    if (title.isEmpty()) title = getString(R.string.recording_screen_title);
    Intent result = new Intent();
    result.setData(Uri.fromFile(audioFile));
    result.putExtra("recordingTitle", title);
    setResult(RESULT_OK, result);
    finish();
  }

  private void leave() {
    if (state == State.RECORDING || state == State.PAUSED || state == State.COMPLETE) {
      confirmDiscard();
    } else {
      finish();
    }
  }

  private void confirmDiscard() {
    dev.wordbyword.app.ui.view.GlassDialogs.confirm(this, "Удалить запись?",
        "Эта запись будет удалена без возможности восстановления.", () -> {
          handler.removeCallbacks(ticker);
          if (preview != null) preview.release();
          if (recorder != null) {
            try {
              recorder.stop();
            } catch (RuntimeException exception) {
              Log.w("RecordingActivity", "Cannot stop recording", exception);
            }
            recorder.release();
            recorder = null;
          }
          if (audioFile != null) audioFile.delete();
          finish();
        });
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != MICROPHONE_PERMISSION) return;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      begin();
    } else {
      new AlertDialog.Builder(this)
          .setMessage(R.string.microphone_permission)
          .setNegativeButton(R.string.not_now, null)
          .setPositiveButton(
              R.string.microphone_settings,
              (dialog, which) ->
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:" + getPackageName()))))
          .show();
    }
  }

  private void render() {
    findViewById(R.id.recording_toolbar).setVisibility(state == State.COMPLETE ? View.GONE : View.VISIBLE);
    ((HomeGlowLayout) findViewById(R.id.recording_root)).setVoiceMode(state != State.COMPLETE);
    findViewById(R.id.recording_active)
        .setVisibility(state == State.COMPLETE ? View.GONE : View.VISIBLE);
    findViewById(R.id.recording_complete)
        .setVisibility(state == State.COMPLETE ? View.VISIBLE : View.GONE);
    findViewById(R.id.recording_start)
        .setVisibility(state == State.READY ? View.VISIBLE : View.GONE);
    findViewById(R.id.recording_pause)
        .setVisibility(
            state == State.RECORDING || state == State.PAUSED ? View.VISIBLE : View.GONE);
    findViewById(R.id.recording_finish)
        .setVisibility(
            state == State.RECORDING || state == State.PAUSED ? View.VISIBLE : View.GONE);
    findViewById(R.id.recording_cancel)
        .setVisibility(state == State.READY ? View.GONE : View.VISIBLE);
    android.widget.ImageButton pause = findViewById(R.id.recording_pause);
    pause.setImageResource(state == State.PAUSED ? R.drawable.ic_play : R.drawable.ic_pause);
    pause.setContentDescription(getString(state == State.PAUSED ? R.string.recording_resume : R.string.recording_pause));
    int status =
        switch (state) {
          case READY -> R.string.recording_ready;
          case RECORDING -> R.string.recording_live;
          case PAUSED -> R.string.recording_paused;
          case COMPLETE -> R.string.recording_done;
        };

    ((TextView) findViewById(R.id.recording_hint)).setText(status);
    renderTimer();
  }

  private void renderTimer() {
    long total =
        elapsedMs + (state == State.RECORDING ? SystemClock.elapsedRealtime() - startedAt : 0);
    long seconds = total / 1000;
    ((TextView) findViewById(R.id.recording_timer))
        .setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
  }

  private void animateOrb() {
    if (recorder == null) return;
    int amplitude = recorder.getMaxAmplitude();
    float level = amplitude <= 40 ? 0f : Math.min(1f, (float) (Math.log1p((amplitude - 40) / 100f) / Math.log1p(20)));
    ((HomeGlowLayout) findViewById(R.id.recording_root)).setVoiceLevel(level);
    orbScale = orbScale * 0.65f + (1f + level * 0.22f) * 0.35f;
    View orb = findViewById(R.id.recording_orb);
    ((VoiceOrbView) orb).setLevel(level);
    orb.animate().scaleX(orbScale).scaleY(orbScale).setDuration(120).start();
  }

  private void resetOrb() {
    orbScale = 1f;
    ((HomeGlowLayout) findViewById(R.id.recording_root)).setVoiceLevel(0);
    View orb = findViewById(R.id.recording_orb);
    ((VoiceOrbView) orb).setLevel(0f);
    orb.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
  }

  private void releaseFailedRecorder() {
    if (recorder != null) {
      recorder.release();
      recorder = null;
    }
    if (audioFile != null) audioFile.delete();
    audioFile = null;
    state = State.READY;
    render();
  }

  private void showFailure() {
    new AlertDialog.Builder(this)
        .setMessage(R.string.recording_failed)
        .setPositiveButton("OK", null)
        .show();
  }
}
