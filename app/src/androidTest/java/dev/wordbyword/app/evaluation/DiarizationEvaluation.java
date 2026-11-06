package dev.wordbyword.app.evaluation;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.diarization.SherpaOnnxSpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.OptionalDouble;

public final class DiarizationEvaluation extends Instrumentation {
  private Bundle arguments;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    this.arguments = arguments == null ? new Bundle() : arguments;
    start();
  }

  @Override
  public void onStart() {
    var result = new Bundle();
    try {
      String id = arguments.getString("recording_id", "");
      String uri = arguments.getString("audio_uri", "");
      if (!id.matches("[a-zA-Z0-9_-]+") || uri.isEmpty())
        throw new IllegalArgumentException("Provide recording_id and audio_uri");
      var target = getTargetContext();
      var context =
          new ProcessingContext() {
            public boolean isCancellationRequested() {
              return Thread.currentThread().isInterrupted();
            }

            public void reportProgress(String stage, OptionalDouble fraction) {}
          };
      var output = target.getCacheDir().toPath().resolve(id + ".hyp.rttm");
      Files.deleteIfExists(output);
      try (var audio = new AndroidAudioPreparer(target).prepare(URI.create(uri), context)) {
        var segments = new SherpaOnnxSpeakerDiarizer(target).diarize(audio, context);
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
          for (var segment : segments) {
            writer.write(
                String.format(
                    Locale.ROOT,
                    "SPEAKER %s 1 %.3f %.3f <NA> <NA> %s <NA> <NA>%n",
                    id,
                    segment.startMs() / 1000.0,
                    (segment.endMs() - segment.startMs()) / 1000.0,
                    segment.speakerId()));
          }
        }
      }
      result.putString("stream", "WBW_EVALUATION_OK\n");
      finish(Activity.RESULT_OK, result);
    } catch (Exception | LinkageError exception) {
      Log.e("DiarizationEvaluation", "Evaluation failed", exception);
      result.putString("stream", "WBW_EVALUATION_FAILED: " + exception + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
