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
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CommonVoiceHypothesisTest {
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
    run(new AndroidAudioPreparer(InstrumentationRegistry.getInstrumentation().getTargetContext()), "common-voice-hypotheses.json");
  }

  static void run(AndroidAudioPreparer preparer, String outputFileName) throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();

    SpeechRecognizer recognizer = new SherpaSpeechRecognizer(targetContext.getAssets());

    JsonArray runs = new JsonArray();
    try (ZipInputStream zip = new ZipInputStream(testAssets.open("audio-native-rate.zip"))) {
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

        PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT);
        RecognitionResult result = recognizer.transcribe(audio, CONTEXT);
        audio.close();
        audioFile.delete();

        JsonObject run = new JsonObject();
        run.addProperty("file", name);
        run.addProperty("status", "READY");
        run.addProperty("hypothesis", result.text());
        runs.add(run);
      }
    }

    JsonObject root = new JsonObject();
    root.add("runs", runs);
    File output = new File(targetContext.getCacheDir(), outputFileName);
    try (FileWriter writer = new FileWriter(output)) {
      new Gson().toJson(root, writer);
    }
  }
}
