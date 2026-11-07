package dev.wordbyword.app.evaluation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SherpaOnnxSpeakerDiarizer;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
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
public class DiarizationHypothesisTest {
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
  public void rec0055() throws Exception {
    produceHypothesis("rec_0055");
  }

  @Test
  public void rec0273() throws Exception {
    produceHypothesis("rec_0273");
  }

  private void produceHypothesis(String recordingId) throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();

    File audioFile = new File(targetContext.getCacheDir(), recordingId + ".flac");
    copy(testAssets.open(recordingId + ".flac"), audioFile);

    AndroidAudioPreparer preparer = new AndroidAudioPreparer(targetContext);
    PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT);
    SpeakerDiarizer diarizer = new SherpaOnnxSpeakerDiarizer(targetContext);
    List<SpeakerDiarizer.Segment> segments = diarizer.diarize(audio, CONTEXT);
    audio.close();
    audioFile.delete();

    File output = new File(targetContext.getCacheDir(), recordingId + ".hyp.rttm");
    try (FileWriter writer = new FileWriter(output)) {
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
