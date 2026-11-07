package dev.wordbyword.app.evaluation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recognition.SherpaSpeechRecognizer;
import dev.wordbyword.app.core.recognition.SpeechRecognizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AsrHypothesisTest {
  private static final ProcessingContext CONTEXT =
      new ProcessingContext() {
        @Override
        public boolean isCancellationRequested() {
          return false;
        }

        @Override
        public void reportProgress(String stage, OptionalDouble fraction) {}
      };

  @Test
  public void allSamples() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();
    var arguments = InstrumentationRegistry.getArguments();
    String archive = arguments.getString("archive", "audio.zip");
    String outputName = arguments.getString("output", "asr-hypotheses.json");

    AndroidAudioPreparer preparer = new AndroidAudioPreparer(targetContext);
    SpeechRecognizer recognizer = new SherpaSpeechRecognizer(targetContext.getAssets());

    JsonArray runs = new JsonArray();
    try (ZipInputStream zip = new ZipInputStream(testAssets.open(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        File audioFile = new File(targetContext.getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(audioFile)) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = zip.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
        }

        JsonObject run = new JsonObject();
        run.addProperty("file", name);
        long started = System.nanoTime();
        try (PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT)) {
          RecognitionResult result = recognizer.transcribe(audio, CONTEXT);
          run.addProperty("status", "READY");
          run.addProperty("hypothesis", result.text());
          run.addProperty("seconds", audio.sampleCount().orElseThrow() / 16000.0);
          run.addProperty("timing", result.timing().name());
          run.add("segments", new Gson().toJsonTree(result.segments()));
        } finally {
          audioFile.delete();
        }
        run.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0);
        runs.add(run);
      }
    }

    JsonObject root = new JsonObject();
    root.add("runs", runs);
    root.addProperty("device", android.os.Build.MODEL);
    root.addProperty("sdk", android.os.Build.VERSION.SDK_INT);
    root.addProperty("archive", archive);
    root.addProperty("runtime", "sherpa-onnx 2.0.1 / bundled GigaAM v2 int8 / ASR threads=2");
    root.addProperty("timing_scope", "AndroidAudioPreparer, VAD and per-file ASR model loading");
    try (var status = new BufferedReader(new FileReader("/proc/self/status"))) {
      String line;
      while ((line = status.readLine()) != null) {
        if (line.startsWith("VmHWM:")) {
          root.addProperty("process_vm_hwm_kb", Long.parseLong(line.replaceAll("[^0-9]", "")));
          break;
        }
      }
    } catch (IOException exception) {
      root.addProperty("memory_read_error", exception.toString());
    }
    File output = new File(targetContext.getCacheDir(), outputName);
    try (FileWriter writer = new FileWriter(output)) {
      new Gson().toJson(root, writer);
    }
  }
}
