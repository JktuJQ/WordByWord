package dev.wordbyword.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SherpaOnnxSpeakerDiarizer;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiarizerCheckActivity extends Activity {
  private static final String TAG = "DiarizerCheck";
  private static final int PICK_AUDIO_REQUEST_CODE = 1;

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  private TextView status;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    int padding = (int) (16 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);

    Button pickButton = new Button(this);
    pickButton.setText("Выбрать аудиофайл");
    pickButton.setOnClickListener(view -> pickAudio());

    Button cancelButton = new Button(this);
    cancelButton.setText("Отмена");
    cancelButton.setOnClickListener(view -> cancelled.set(true));

    status = new TextView(this);
    status.setText("Выберите файл для проверки диаризации");

    root.addView(pickButton);
    root.addView(cancelButton);
    root.addView(status);
    setContentView(root);

    String testUri = getIntent().getStringExtra("audio_uri");
    if (testUri != null) {
      long cancelAfterMs = getIntent().getLongExtra("cancel_after_ms", 0);
      runCheck(Uri.parse(testUri), cancelAfterMs);
    }
  }

  private void pickAudio() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("audio/*");
    startActivityForResult(intent, PICK_AUDIO_REQUEST_CODE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == PICK_AUDIO_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri != null) {
        runCheck(uri, 0);
      }
    }
  }

  private void runCheck(Uri uri, long cancelAfterMs) {
    cancelled.set(false);
    setStatus("Диаризация: " + uri);

    if (cancelAfterMs > 0) {
      main.postDelayed(() -> cancelled.set(true), cancelAfterMs);
    }

    ProcessingContext context =
        new ProcessingContext() {
          @Override
          public boolean isCancellationRequested() {
            return cancelled.get();
          }

          @Override
          public void reportProgress(String stage, OptionalDouble fraction) {
            Log.d(TAG, stage + " " + fraction);
          }
        };

    worker.execute(
        () -> {
          long startNanos = System.nanoTime();
          try {
            AndroidAudioPreparer preparer = new AndroidAudioPreparer(getApplicationContext());
            PcmAudio audio = preparer.prepare(java.net.URI.create(uri.toString()), context);
            SpeakerDiarizer diarizer = new SherpaOnnxSpeakerDiarizer(getApplicationContext());
            List<SpeakerDiarizer.Segment> segments = diarizer.diarize(audio, context);
            audio.close();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            for (SpeakerDiarizer.Segment segment : segments) {
              Log.i(
                  TAG,
                  segment.speakerId() + " [" + segment.startMs() + " - " + segment.endMs() + "]");
            }
            String message = "OK: segments=" + segments.size() + " elapsedMs=" + elapsedMs;
            Log.i(TAG, message);
            setStatus(message);
          } catch (ProcessingException exception) {
            Log.e(TAG, "ProcessingException: " + exception.getMessage(), exception);
            setStatus("Ошибка: " + exception.getMessage());
          } catch (CancellationException exception) {
            Log.i(TAG, "Cancelled");
            setStatus("Отменено");
          } catch (Exception exception) {
            Log.e(TAG, "Unexpected failure", exception);
            setStatus("Неожиданная ошибка: " + exception);
          }
        });
  }

  private void setStatus(String message) {
    main.post(
        () -> {
          status.setText(message);
          Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
  }
}
