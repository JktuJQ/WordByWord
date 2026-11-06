package dev.wordbyword.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.ui.AppState;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AsrDeviceCheck extends Instrumentation {
  private Bundle arguments;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  @Override
  public void onStart() {
    Bundle output = new Bundle();
    JSONObject report = new JSONObject();
    Activity activity = null;
    try {
      report.put("run_id", arguments.getString("run_id"));
      activity =
          startActivitySync(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      var app = (WordByWordApplication) getTargetContext().getApplicationContext();
      JSONArray runs = new JSONArray();
      report.put("runs", runs);
      if ("benchmark".equals(arguments.getString("only"))) {
        for (String name : arguments.getString("files").split(","))
          runs.put(run(app, name, false, false));
      } else {
        if (!"cancel".equals(arguments.getString("only"))) {
          for (String name :
              new String[] {"rec_0055.flac", "rec_0273.flac", "silence.wav", "short.wav"})
            runs.put(run(app, name, false, false));
        }
        runs.put(run(app, "rec_0055.flac", true, false));
        if (!"cancel".equals(arguments.getString("only"))) {
          File encoder =
              new File(app.getExternalFilesDir(null), "models/gigaam-v2/encoder.int8.onnx");
          File hidden = new File(encoder.getParentFile(), "encoder.saved");
          if (!encoder.renameTo(hidden)) throw new IllegalStateException("Cannot hide encoder");
          try {
            runs.put(run(app, "rec_0055.flac", false, true));
          } finally {
            if (!hidden.renameTo(encoder))
              throw new IllegalStateException("Cannot restore encoder");
          }
        }
      }
      File[] remaining = app.getCacheDir().listFiles((dir, name) -> name.endsWith(".pcm"));
      if (remaining == null || remaining.length != 0) throw new AssertionError("Leaked PCM");
      report.put("success", true);
    } catch (Throwable failure) {
      try {
        report.put("success", false).put("error", android.util.Log.getStackTraceString(failure));
      } catch (Exception ignored) {
      }
    } finally {
      if (activity != null) {
        Activity opened = activity;
        runOnMainSync(opened::finish);
      }
    }
    try {
      Files.write(
          new File(getTargetContext().getFilesDir(), "asr-checks.json").toPath(),
          report.toString(2).getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      output.putString("error", failure.toString());
    }
    output.putString("stream", report.toString());
    finish(report.optBoolean("success") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
  }

  private JSONObject run(WordByWordApplication app, String name, boolean cancel, boolean missing)
      throws Exception {
    String id = name + System.nanoTime();
    File file = new File(app.getExternalFilesDir(null), "checks/" + name);
    var recording = new Recording(id, file.toURI(), name, OptionalLong.empty());
    long started = SystemClock.elapsedRealtime();
    runOnMainSync(() -> app.controller().open(recording));
    AppState.Item item;
    long cancelAt = 0;
    while (true) {
      var current = new AtomicReference<AppState>();
      runOnMainSync(() -> current.set(app.controller().state()));
      item = current.get().find(id);
      if (item != null
          && item.status() != AppState.Status.IMPORTED
          && item.status() != AppState.Status.PROCESSING) break;
      if (cancel && cancelAt == 0 && item != null && item.detail().equals("Распознаём речь")) {
        cancelAt = SystemClock.elapsedRealtime();
        runOnMainSync(() -> app.controller().cancel());
      }
      if (SystemClock.elapsedRealtime() - started > 180000)
        throw new AssertionError("Timed out: " + name);
      Thread.sleep(50);
    }
    JSONObject result =
        new JSONObject()
            .put("file", name)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("status", item.status().name())
            .put("pss_after_kib", Debug.getPss());
    if (cancel) {
      if (item.status() != AppState.Status.CANCELLED || item.transcript() != null || cancelAt == 0)
        throw new AssertionError(
            "Cancellation failed: status="
                + item.status()
                + ", detail="
                + item.detail()
                + ", cancelAt="
                + cancelAt);
      result.put("cancel_latency_ms", SystemClock.elapsedRealtime() - cancelAt);
    } else if (missing) {
      if (item.status() != AppState.Status.ERROR || !item.detail().contains("Модели"))
        throw new AssertionError("Missing model not reported: " + item.detail());
      result.put("error", item.detail());
    } else {
      if (item.status() != AppState.Status.READY) throw new AssertionError(item.detail());
      JSONArray segments = new JSONArray();
      long duration = item.transcript().recording().durationMs().orElseThrow();
      long previous = 0;
      for (var utterance : item.transcript().utterances()) {
        if (utterance.startMs() < previous
            || utterance.endMs() < utterance.startMs()
            || utterance.endMs() > duration) throw new AssertionError("Invalid timestamp");
        previous = utterance.endMs();
        if (utterance.speakerId().isPresent()) throw new AssertionError("Invented speaker");
        segments.put(
            new JSONObject()
                .put("text", utterance.text())
                .put("start_ms", utterance.startMs())
                .put("end_ms", utterance.endMs()));
      }
      if (name.endsWith(".flac") && segments.length() == 0)
        throw new AssertionError("No recognized speech");
      if (name.equals("silence.wav") && segments.length() != 0)
        throw new AssertionError("Hallucinated silence");
      result.put("duration_ms", duration).put("segments", segments);
    }
    result.put(
        "proc_status",
        new String(
            Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.UTF_8));
    if ("benchmark".equals(arguments.getString("only")))
      runOnMainSync(() -> app.controller().removeVisible(java.util.Set.of(id)));
    return result;
  }
}
