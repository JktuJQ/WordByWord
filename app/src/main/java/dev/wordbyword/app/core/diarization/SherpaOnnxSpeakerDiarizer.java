package dev.wordbyword.app.core.diarization;

import android.content.Context;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

public final class SherpaOnnxSpeakerDiarizer implements SpeakerDiarizer {
  private static final String SEGMENTATION_MODEL_ASSET = "diarization/segmentation.int8.onnx";
  private static final String EMBEDDING_MODEL_ASSET = "diarization/embedding.onnx";
  private static final float CLUSTERING_THRESHOLD = 0.8f;
  private static final float MIN_DURATION_ON = 0.3f;
  private static final float MIN_DURATION_OFF = 0.5f;
  private static final String STAGE_DIARIZING = "Диаризация";

  private final Context context;

  public SherpaOnnxSpeakerDiarizer(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public List<Segment> diarize(PcmAudio audio, ProcessingContext processingContext)
      throws ProcessingException {
    processingContext.throwIfCancelled();
    float[] samples = readAllSamples(audio, processingContext);
    processingContext.throwIfCancelled();

    OfflineSpeakerDiarization diarization;
    try {
      diarization = new OfflineSpeakerDiarization(context.getAssets(), buildConfig());
    } catch (RuntimeException | LinkageError exception) {
      throw new ProcessingException("Не удалось загрузить модели диаризации", exception);
    }

    try {
      if (diarization.sampleRate() != PcmAudio.SAMPLE_RATE_HZ) {
        throw new ProcessingException(
            "Диаризация ожидает частоту "
                + diarization.sampleRate()
                + " Гц, получено аудио "
                + PcmAudio.SAMPLE_RATE_HZ
                + " Гц");
      }

      processingContext.throwIfCancelled();
      processingContext.reportProgress(STAGE_DIARIZING, OptionalDouble.empty());

      OfflineSpeakerDiarizationSegment[] segments;
      try {
        segments = diarization.process(samples);
      } catch (RuntimeException | LinkageError exception) {
        throw new ProcessingException("Ошибка диаризации записи", exception);
      }

      processingContext.throwIfCancelled();
      List<Segment> result = new ArrayList<>(segments.length);
      for (OfflineSpeakerDiarizationSegment segment : segments) {
        result.add(toSegment(segment.getStart(), segment.getEnd(), segment.getSpeaker()));
      }
      return result;
    } finally {
      diarization.release();
    }
  }

  private static OfflineSpeakerDiarizationConfig buildConfig() {
    OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
        new OfflineSpeakerSegmentationPyannoteModelConfig(SEGMENTATION_MODEL_ASSET, 0.1f);
    OfflineSpeakerSegmentationModelConfig segmentation =
        new OfflineSpeakerSegmentationModelConfig(pyannote, 1, false, "cpu");
    SpeakerEmbeddingExtractorConfig embedding =
        new SpeakerEmbeddingExtractorConfig(EMBEDDING_MODEL_ASSET, 1, false, "cpu");
    FastClusteringConfig clustering = new FastClusteringConfig(-1, CLUSTERING_THRESHOLD, false);
    return new OfflineSpeakerDiarizationConfig(
        segmentation, embedding, clustering, MIN_DURATION_ON, MIN_DURATION_OFF);
  }

  static float[] readAllSamples(PcmAudio audio, ProcessingContext context)
      throws ProcessingException {
    context.throwIfCancelled();
    long expected = audio.sampleCount().orElse(PcmAudio.SAMPLE_RATE_HZ);
    int maximum = Integer.MAX_VALUE - 8;
    if (expected < 0 || expected > maximum)
      throw new ProcessingException("Аудио слишком велико или содержит неверное число сэмплов");
    float[] buffer = new float[(int) Math.max(expected, 1)];
    float[] probe = new float[1];
    int total = 0;
    try (PcmAudio.Reader reader = audio.openReader()) {
      while (true) {
        context.throwIfCancelled();
        boolean full = total == buffer.length;
        int length = full ? 1 : Math.min(PcmAudio.SAMPLE_RATE_HZ, buffer.length - total);
        int read = reader.read(full ? probe : buffer, full ? 0 : total, length);
        context.throwIfCancelled();
        if (read == -1) break;
        if (read <= 0 || read > length) throw new IOException("Invalid PCM reader result");
        if (full) {
          if (total == maximum) throw new IOException("PCM exceeds maximum array size");
          int capacity = (int) Math.min(maximum, (long) buffer.length * 2);
          buffer = Arrays.copyOf(buffer, capacity);
          buffer[total] = probe[0];
        }
        total += read;
      }
    } catch (IOException exception) {
      throw new ProcessingException(
          "Не удалось прочитать подготовленное аудио для диаризации", exception);
    }
    context.throwIfCancelled();
    return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
  }

  static Segment toSegment(float startSeconds, float endSeconds, int speakerIndex) {
    long startMs = Math.round((double) startSeconds * 1000.0);
    long endMs = Math.max(startMs, Math.round((double) endSeconds * 1000.0));
    return new Segment(speakerId(speakerIndex), startMs, endMs);
  }

  static String speakerId(int speakerIndex) {
    return "speaker_" + speakerIndex;
  }
}
