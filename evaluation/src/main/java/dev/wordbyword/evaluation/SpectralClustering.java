package dev.wordbyword.evaluation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class SpectralClustering {
  public static int[] cluster(List<double[]> vectors, double fraction) {
    int n = vectors.size();
    int k = SpectralSpeakerCount.select(vectors, fraction);
    if (k == 1) return new int[n];
    double[][] affinity = new double[n][n];
    int neighbors = Math.max(1, (int) Math.ceil((n - 1) * fraction));
    for (int i = 0; i < n; i++) {
      final int row = i;
      Integer[] indices = new Integer[n - 1];
      int position = 0;
      for (int j = 0; j < n; j++) if (j != i) indices[position++] = j;
      Arrays.sort(indices, Comparator.comparingDouble(j -> EmbeddingClusters.distance(vectors.get(row), vectors.get(j))));
      for (int j = 0; j < neighbors; j++) {
        int column = indices[j];
        double weight = Math.max(0, 1 - EmbeddingClusters.distance(vectors.get(i), vectors.get(column))) / 2;
        affinity[i][column] += weight; affinity[column][i] += weight;
      }
    }
    double[] degree = new double[n];
    for (int i = 0; i < n; i++) for (double value : affinity[i]) degree[i] += value;
    double[][] laplacian = new double[n][n];
    for (int i = 0; i < n; i++) {
      laplacian[i][i] = degree[i] == 0 ? 0 : 1;
      for (int j = 0; j < n; j++) if (degree[i] > 0 && degree[j] > 0)
        laplacian[i][j] -= affinity[i][j] / Math.sqrt(degree[i] * degree[j]);
    }
    double[][] eigenvectors = SpectralSpeakerCount.eigensystem(laplacian).vectors();
    double[][] points = new double[n][];
    for (int i = 0; i < n; i++) points[i] = EmbeddingClusters.normalize(Arrays.copyOf(eigenvectors[i], k));
    int[] best = null;
    double bestLoss = Double.POSITIVE_INFINITY;
    for (int restart = 0; restart < Math.min(3, n); restart++) {
      double[][] centers = new double[k][];
      centers[0] = points[restart * n / Math.min(3, n)].clone();
      for (int c = 1; c < k; c++) {
        int farthest = 0; double maximum = -1;
        for (int i = 0; i < n; i++) {
          double distance = Double.POSITIVE_INFINITY;
          for (int j = 0; j < c; j++) distance = Math.min(distance, squared(points[i], centers[j]));
          if (distance > maximum) { maximum = distance; farthest = i; }
        }
        centers[c] = points[farthest].clone();
      }
      int[] labels = new int[n]; Arrays.fill(labels, -1);
      for (int iteration = 0; iteration < 100; iteration++) {
        boolean changed = false;
        for (int i = 0; i < n; i++) {
          int winner = 0;
          for (int c = 1; c < k; c++) if (squared(points[i], centers[c]) < squared(points[i], centers[winner])) winner = c;
          if (labels[i] != winner) { labels[i] = winner; changed = true; }
        }
        double[][] sums = new double[k][k]; int[] counts = new int[k];
        for (int i = 0; i < n; i++) {
          counts[labels[i]]++;
          for (int d = 0; d < k; d++) sums[labels[i]][d] += points[i][d];
        }
        for (int c = 0; c < k; c++) if (counts[c] > 0) {
          for (int d = 0; d < k; d++) sums[c][d] /= counts[c];
          centers[c] = sums[c];
        }
        if (!changed) break;
      }
      double loss = 0;
      for (int i = 0; i < n; i++) loss += squared(points[i], centers[labels[i]]);
      if (loss < bestLoss) { bestLoss = loss; best = labels.clone(); }
    }
    return best;
  }

  private static double squared(double[] a, double[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) sum += (a[i] - b[i]) * (a[i] - b[i]);
    return sum;
  }
}
