package dev.wordbyword.app.core.diarization;

import android.content.Context;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
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
  private static final float BOUNDARY_CLUSTERING_THRESHOLD = 0.8f;
  private static final float EMBEDDING_CLUSTER_THRESHOLD = 0.45f;
  private static final float MIN_FOR_EMBEDDING_SECONDS = 0.3f;
  private static final float ANCHOR_SECONDS = 1.5f;
  private static final float MAX_EMBEDDING_SECONDS = 8f;
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

    float[][] boundaries;
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

      boundaries = new float[segments.length][2];
      for (int i = 0; i < segments.length; i++) {
        boundaries[i][0] = segments[i].getStart();
        boundaries[i][1] = segments[i].getEnd();
      }
    } finally {
      diarization.release();
    }

    processingContext.throwIfCancelled();
    int[] clusters = clusterBySpeakerVoice(samples, boundaries, processingContext);

    List<Segment> result = new ArrayList<>(boundaries.length);
    for (int i = 0; i < boundaries.length; i++) {
      result.add(toSegment(boundaries[i][0], boundaries[i][1], clusters[i]));
    }
    return result;
  }

  private int[] clusterBySpeakerVoice(
      float[] samples, float[][] boundaries, ProcessingContext processingContext)
      throws ProcessingException {
    SpeakerEmbeddingExtractorConfig embeddingConfig =
        new SpeakerEmbeddingExtractorConfig(EMBEDDING_MODEL_ASSET, 1, false, "cpu");
    SpeakerEmbeddingExtractor extractor;
    try {
      extractor = new SpeakerEmbeddingExtractor(context.getAssets(), embeddingConfig);
    } catch (RuntimeException | LinkageError exception) {
      throw new ProcessingException("Не удалось загрузить модель голосовых эмбеддингов", exception);
    }

    try {
      double[][] embeddings = new double[boundaries.length][];
      for (int i = 0; i < boundaries.length; i++) {
        processingContext.throwIfCancelled();
        float duration = boundaries[i][1] - boundaries[i][0];
        if (duration < MIN_FOR_EMBEDDING_SECONDS) continue;
        embeddings[i] = embed(extractor, samples, boundaries[i][0], duration);
      }

      List<Integer> anchors = new ArrayList<>();
      for (int i = 0; i < boundaries.length; i++) {
        if (embeddings[i] != null && boundaries[i][1] - boundaries[i][0] >= ANCHOR_SECONDS) {
          anchors.add(i);
        }
      }
      processingContext.throwIfCancelled();

      List<double[]> anchorVectors = new ArrayList<>(anchors.size());
      for (int anchor : anchors) anchorVectors.add(embeddings[anchor]);
      int[] anchorLabels =
          anchorVectors.isEmpty()
              ? new int[0]
              : clusterByThreshold(anchorVectors, EMBEDDING_CLUSTER_THRESHOLD);
      int groups = anchorLabels.length == 0 ? 0 : Arrays.stream(anchorLabels).max().orElseThrow() + 1;

      List<double[]> centroids = new ArrayList<>(groups);
      for (int group = 0; group < groups; group++) {
        List<double[]> members = new ArrayList<>();
        for (int i = 0; i < anchorLabels.length; i++) {
          if (anchorLabels[i] == group) members.add(anchorVectors.get(i));
        }
        centroids.add(centroid(members));
      }

      int[] labels = new int[boundaries.length];
      Arrays.fill(labels, -1);
      for (int i = 0; i < anchors.size(); i++) labels[anchors.get(i)] = anchorLabels[i];
      for (int i = 0; i < boundaries.length; i++) {
        if (labels[i] == -1 && embeddings[i] != null && !centroids.isEmpty()) {
          labels[i] = nearestCentroid(centroids, embeddings[i]);
        }
      }
      for (int i = 0; i < boundaries.length; i++) {
        if (labels[i] != -1) continue;
        labels[i] = nearestAssignedByTime(boundaries, labels, i);
      }
      return labels;
    } finally {
      extractor.release();
    }
  }

  private static double[] embed(
      SpeakerEmbeddingExtractor extractor, float[] samples, float start, float duration) {
    float length = Math.min(duration, MAX_EMBEDDING_SECONDS);
    int from = Math.max(0, Math.round((start + (duration - length) / 2) * PcmAudio.SAMPLE_RATE_HZ));
    int to = Math.min(samples.length, from + Math.round(length * PcmAudio.SAMPLE_RATE_HZ));
    OnlineStream stream = extractor.createStream();
    try {
      stream.acceptWaveform(Arrays.copyOfRange(samples, from, to), PcmAudio.SAMPLE_RATE_HZ);
      stream.inputFinished();
      return normalize(toDouble(extractor.compute(stream)));
    } finally {
      stream.release();
    }
  }

  static int[] clusterByThreshold(List<double[]> vectors, double threshold) {
    int n = vectors.size();
    double[][] distances = new double[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        distances[i][j] = distances[j][i] = cosineDistance(vectors.get(i), vectors.get(j));
      }
    }
    List<List<Integer>> groups = new ArrayList<>();
    for (int i = 0; i < n; i++) groups.add(new ArrayList<>(List.of(i)));
    while (groups.size() > 1) {
      int left = -1, right = -1;
      double best = Double.POSITIVE_INFINITY;
      for (int i = 0; i < groups.size(); i++) {
        for (int j = i + 1; j < groups.size(); j++) {
          double sum = 0;
          for (int a : groups.get(i)) for (int b : groups.get(j)) sum += distances[a][b];
          double mean = sum / (groups.get(i).size() * groups.get(j).size());
          if (mean < best) {
            best = mean;
            left = i;
            right = j;
          }
        }
      }
      if (best > threshold) break;
      groups.get(left).addAll(groups.get(right));
      groups.remove(right);
    }
    int[] labels = new int[n];
    for (int group = 0; group < groups.size(); group++) {
      for (int item : groups.get(group)) labels[item] = group;
    }
    return labels;
  }

  private static int nearestCentroid(List<double[]> centroids, double[] vector) {
    int best = 0;
    for (int i = 1; i < centroids.size(); i++) {
      if (cosineDistance(centroids.get(i), vector) < cosineDistance(centroids.get(best), vector)) {
        best = i;
      }
    }
    return best;
  }

  static int nearestAssignedByTime(float[][] boundaries, int[] labels, int index) {
    int nearest = -1;
    float bestDistance = Float.MAX_VALUE;
    for (int j = 0; j < boundaries.length; j++) {
      if (labels[j] == -1) continue;
      float distance = Math.abs(boundaries[j][0] - boundaries[index][0]);
      if (distance < bestDistance) {
        bestDistance = distance;
        nearest = j;
      }
    }
    return nearest == -1 ? 0 : labels[nearest];
  }

  static double cosineDistance(double[] a, double[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
    return Math.max(0, Math.min(2, 1 - dot));
  }

  private static double[] centroid(List<double[]> vectors) {
    double[] sum = new double[vectors.get(0).length];
    for (double[] vector : vectors) {
      for (int i = 0; i < sum.length; i++) sum[i] += vector[i];
    }
    for (int i = 0; i < sum.length; i++) sum[i] /= vectors.size();
    return normalize(sum);
  }

  static double[] normalize(double[] vector) {
    double norm = 0;
    for (double value : vector) norm += value * value;
    double length = Math.sqrt(norm);
    double[] result = new double[vector.length];
    for (int i = 0; i < vector.length; i++) result[i] = vector[i] / length;
    return result;
  }

  private static double[] toDouble(float[] values) {
    double[] result = new double[values.length];
    for (int i = 0; i < values.length; i++) result[i] = values[i];
    return result;
  }

  private static OfflineSpeakerDiarizationConfig buildConfig() {
    OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
        new OfflineSpeakerSegmentationPyannoteModelConfig(SEGMENTATION_MODEL_ASSET, 0.1f);
    OfflineSpeakerSegmentationModelConfig segmentation =
        new OfflineSpeakerSegmentationModelConfig(pyannote, 1, false, "cpu");
    SpeakerEmbeddingExtractorConfig embedding =
        new SpeakerEmbeddingExtractorConfig(EMBEDDING_MODEL_ASSET, 1, false, "cpu");
    FastClusteringConfig clustering =
        new FastClusteringConfig(-1, BOUNDARY_CLUSTERING_THRESHOLD, false);
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
