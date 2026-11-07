package dev.wordbyword.app.evaluation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.wordbyword.app.WordByWordApplication;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.export.TranscriptJsonExporter;
import dev.wordbyword.app.ui.AppState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InferenceBenchmarkTest {
  private static final String RECORDING_ID = "benchmark-rec-0055";
  private static final String RESULT_FILE = "benchmark-transcript.json";

  @Test
  public void processesRec0055AndExportsTranscript() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var app = (WordByWordApplication) instrumentation.getTargetContext().getApplicationContext();
    var audioFile = new File(app.getCacheDir(), RECORDING_ID + ".flac");
    var outputFile = new File(app.getCacheDir(), RESULT_FILE);
    Files.deleteIfExists(outputFile.toPath());

    try (InputStream input = instrumentation.getContext().getAssets().open("rec_0055.flac");
        var output = new FileOutputStream(audioFile)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    }

    Recording imported =
        app.repository()
            .saveRecording(
                new Recording(
                    RECORDING_ID, audioFile.toURI(), "rec_0055.flac", OptionalLong.of(60_000)));
    Files.deleteIfExists(audioFile.toPath());

    var completed = new CountDownLatch(1);
    var transcript = new AtomicReference<Transcript>();
    var failure = new AtomicReference<String>();
    instrumentation.runOnMainSync(
        () -> {
          app.controller()
              .attach(
                  state -> {
                    AppState.Item item = state.find(RECORDING_ID);
                    if (item == null) return;
                    if (item.status() == AppState.Status.READY) {
                      transcript.set(item.transcript());
                      completed.countDown();
                    } else if (item.status() == AppState.Status.ERROR
                        || item.status() == AppState.Status.CANCELLED) {
                      failure.set(item.detail());
                      completed.countDown();
                    }
                  });
          app.controller().add(imported);
          app.controller().start(RECORDING_ID);
        });

    assertTrue("Pipeline timed out after 600 seconds", completed.await(600, TimeUnit.SECONDS));
    instrumentation.runOnMainSync(app.controller()::detach);
    assertTrue("Pipeline failed: " + failure.get(), failure.get() == null);
    Transcript result = transcript.get();
    assertNotNull("Pipeline completed without a transcript", result);

    try (var writer = Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8)) {
      TranscriptJsonExporter.write(result, writer);
    }
  }
}
