package dev.wordbyword.app.evaluation;

import android.content.Context;
import android.content.res.AssetManager;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalDouble;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiarizationDenseSweepTest {
  private static final String ASSET_DIR = "diarization-candidates/";
  private static final float MIN_DURATION_ON = 0.3f;
  private static final float MIN_DURATION_OFF = 0.5f;
  private static final int THREADS = 1;

  private static final ProcessingContext CONTEXT =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return false;
        }

        public void reportProgress(String s, OptionalDouble f) {}
      };

  @Test
  public void sweep() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var arguments = InstrumentationRegistry.getArguments();
    String segmentationLabel = arguments.getString("segmentation", "pyannote");
    String segmentationAsset =
        ASSET_DIR + arguments.getString("segmentationAsset", "segmentation.int8.onnx");
    String[] embeddings =
        arguments.getString("embeddings", "baseline=baseline_embedding.onnx").split(",");
    float from = Float.parseFloat(arguments.getString("from", "0.60"));
    float to = Float.parseFloat(arguments.getString("to", "0.85"));
    float step = Float.parseFloat(arguments.getString("step", "0.025"));

    String[] recordingIds =
        arguments.getString("recordings", "rec_0273,dialogs_md,dialogs_ms").split(",");
    var testAssets = instrumentation.getContext().getAssets();
    var targetContext = instrumentation.getTargetContext();
    var preparer = new AndroidAudioPreparer(targetContext);

    for (String recordingId : recordingIds) {
      String extension = recordingId.startsWith("rec_") ? ".flac" : ".wav";
      File audioFile = new File(targetContext.getCacheDir(), recordingId + extension);
      copy(testAssets.open(recordingId + extension), audioFile);
      float[] samples;
      try (PcmAudio audio = preparer.prepare(audioFile.toURI(), CONTEXT)) {
        samples = readAllSamples(audio);
      }
      audioFile.delete();

      for (String embedding : embeddings) {
        String[] pair = embedding.split("=", 2);
        for (int i = 0; from + i * step <= to + 1e-4; i++) {
          float threshold = from + i * step;
          var segments =
              diarize(testAssets, segmentationAsset, ASSET_DIR + pair[1], threshold, samples);
          String name =
              String.format(
                  Locale.ROOT,
                  "dense_%s_%s_%.3f_%s.hyp.rttm",
                  segmentationLabel,
                  pair[0],
                  threshold,
                  recordingId);
          writeRttm(targetContext, name, recordingId, segments);
        }
      }
    }
  }

  private static OfflineSpeakerDiarizationSegment[] diarize(
      AssetManager assets,
      String segmentationAsset,
      String embeddingAsset,
      float threshold,
      float[] samples) {
    var pyannote = new OfflineSpeakerSegmentationPyannoteModelConfig(segmentationAsset, 0.1f);
    var segmentation = new OfflineSpeakerSegmentationModelConfig(pyannote, THREADS, false, "cpu");
    var embedding = new SpeakerEmbeddingExtractorConfig(embeddingAsset, THREADS, false, "cpu");
    var clustering = new FastClusteringConfig(-1, threshold, false);
    var config =
        new OfflineSpeakerDiarizationConfig(
            segmentation, embedding, clustering, MIN_DURATION_ON, MIN_DURATION_OFF);
    var diarization = new OfflineSpeakerDiarization(assets, config);
    try {
      return diarization.process(samples);
    } finally {
      diarization.release();
    }
  }

  private static void writeRttm(
      Context targetContext,
      String fileName,
      String recordingId,
      OfflineSpeakerDiarizationSegment[] segments)
      throws Exception {
    try (FileWriter writer = new FileWriter(new File(targetContext.getCacheDir(), fileName))) {
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
        if (total == buffer.length)
          buffer = Arrays.copyOf(buffer, buffer.length + PcmAudio.SAMPLE_RATE_HZ);
        int n = reader.read(buffer, total, buffer.length - total);
        if (n == -1) break;
        total += n;
      }
    }
    return Arrays.copyOf(buffer, total);
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
