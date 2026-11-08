package dev.wordbyword.app.evaluation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
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
public class DiarizationDurationSweepTest {
  private static final String SEGMENTATION_ASSET = "diarization-candidates/segmentation.int8.onnx";
  private static final String EMBEDDING_ASSET = "diarization-candidates/baseline_embedding.onnx";
  private static final float THRESHOLD = 0.80f;

  private static final float[] MIN_DURATION_ON = {0.3f, 0.15f};
  private static final float[] MIN_DURATION_OFF = {0.5f, 0.3f, 0.2f, 0.1f};

  private static final String[] RECORDING_IDS = {"rec_0273", "dialogs_md", "dialogs_ms"};

  private static final ProcessingContext CONTEXT =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return false;
        }

        public void reportProgress(String s, OptionalDouble f) {}
      };

  @Test
  public void sweepDurations() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();
    var preparer = new AndroidAudioPreparer(targetContext);

    for (String recordingId : RECORDING_IDS) {
      String extension = recordingId.startsWith("rec_") ? ".flac" : ".wav";
      File audioFile = new File(targetContext.getCacheDir(), recordingId + extension);
      copy(testAssets.open(recordingId + extension), audioFile);

      float[] samples;
      try (PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT)) {
        samples = readAllSamples(audio);
      }
      audioFile.delete();

      for (float minOn : MIN_DURATION_ON) {
        for (float minOff : MIN_DURATION_OFF) {
          List<OfflineSpeakerDiarizationSegment> segments =
              diarize(testAssets, minOn, minOff, samples);
          writeRttm(targetContext, minOn, minOff, recordingId, segments);
        }
      }
    }
  }

  private static List<OfflineSpeakerDiarizationSegment> diarize(
      android.content.res.AssetManager assets, float minOn, float minOff, float[] samples) {
    var pyannote = new OfflineSpeakerSegmentationPyannoteModelConfig(SEGMENTATION_ASSET, 0.1f);
    var segmentation = new OfflineSpeakerSegmentationModelConfig(pyannote, 1, false, "cpu");
    var embedding = new SpeakerEmbeddingExtractorConfig(EMBEDDING_ASSET, 1, false, "cpu");
    var clustering = new FastClusteringConfig(-1, THRESHOLD, false);
    var config =
        new OfflineSpeakerDiarizationConfig(segmentation, embedding, clustering, minOn, minOff);
    var diarization = new OfflineSpeakerDiarization(assets, config);
    try {
      return List.of(diarization.process(samples));
    } finally {
      diarization.release();
    }
  }

  private static void writeRttm(
      android.content.Context targetContext,
      float minOn,
      float minOff,
      String recordingId,
      List<OfflineSpeakerDiarizationSegment> segments)
      throws Exception {
    String fileName =
        String.format(Locale.ROOT, "on%.2f_off%.2f_%s.hyp.rttm", minOn, minOff, recordingId);
    File output = new File(targetContext.getCacheDir(), fileName);
    try (FileWriter writer = new FileWriter(output)) {
      for (OfflineSpeakerDiarizationSegment segment : segments) {
        writer.write(
            String.format(
                Locale.ROOT,
                "SPEAKER %s 1 %.3f %.3f <NA> <NA> speaker_%d <NA> <NA>%n",
                recordingId,
                segment.getStart(),
                segment.getEnd() - segment.getStart(),
                segment.getSpeaker()));
      }
    }
  }

  private static float[] readAllSamples(PcmAudio audio) throws Exception {
    long expected = audio.sampleCount().orElse(PcmAudio.SAMPLE_RATE_HZ);
    float[] buffer = new float[(int) Math.max(expected, 1)];
    int total = 0;
    try (var reader = audio.openReader()) {
      while (true) {
        if (total == buffer.length) buffer = growBy(buffer, PcmAudio.SAMPLE_RATE_HZ);
        int n = reader.read(buffer, total, buffer.length - total);
        if (n == -1) break;
        total += n;
      }
    }
    return total == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, total);
  }

  private static float[] growBy(float[] array, int extra) {
    return java.util.Arrays.copyOf(array, array.length + extra);
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
