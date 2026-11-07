package dev.wordbyword.app.evaluation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SherpaOnnxSpeakerDiarizer;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recognition.SherpaSpeechRecognizer;
import dev.wordbyword.app.core.recognition.SpeechRecognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DialogsHypothesisTest {
  private static final String[] RECORDINGS = {"dialogs_md", "dialogs_ms", "dialogs_msd"};

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
  public void processAllDialogs() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();

    AndroidAudioPreparer preparer = new AndroidAudioPreparer(targetContext);
    SpeakerDiarizer diarizer = new SherpaOnnxSpeakerDiarizer(targetContext);
    SpeechRecognizer recognizer = new SherpaSpeechRecognizer(targetContext.getAssets());

    JsonArray runs = new JsonArray();
    for (String recordingId : RECORDINGS) {
      File audioFile = new File(targetContext.getCacheDir(), recordingId + ".wav");
      copy(testAssets.open(recordingId + ".wav"), audioFile);

      PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT);
      List<SpeakerDiarizer.Segment> segments = diarizer.diarize(audio, CONTEXT);
      RecognitionResult recognition = recognizer.transcribe(audio, CONTEXT);
      audio.close();
      audioFile.delete();

      File rttm = new File(targetContext.getCacheDir(), recordingId + ".hyp.rttm");
      try (FileWriter writer = new FileWriter(rttm)) {
        for (SpeakerDiarizer.Segment segment : segments) {
          double startSeconds = segment.startMs() / 1000.0;
          double durationSeconds = (segment.endMs() - segment.startMs()) / 1000.0;
          writer.write(
              String.format(
                  Locale.ROOT,
                  "SPEAKER %s 1 %.3f %.3f <NA> <NA> %s <NA> <NA>%n",
                  recordingId,
                  startSeconds,
                  durationSeconds,
                  segment.speakerId()));
        }
      }

      JsonObject run = new JsonObject();
      run.addProperty("file", "audio/" + recordingId + ".wav");
      run.addProperty("status", "READY");
      run.addProperty("hypothesis", recognition.text());
      runs.add(run);
    }

    JsonObject root = new JsonObject();
    root.add("runs", runs);
    File asrOutput = new File(targetContext.getCacheDir(), "dialogs-asr-hypotheses.json");
    try (FileWriter writer = new FileWriter(asrOutput)) {
      new Gson().toJson(root, writer);
    }
  }

  private static void copy(InputStream in, File destination) throws Exception {
    try (InputStream input = in;
        OutputStream output = new FileOutputStream(destination)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    }
  }
}
