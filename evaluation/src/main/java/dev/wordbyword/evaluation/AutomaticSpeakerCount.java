package dev.wordbyword.evaluation;

import java.util.List;

public final class AutomaticSpeakerCount {
  public static int select(List<double[]> vectors, double singleVoiceDistance) {
    int n = vectors.size();
    if (n == 0) throw new IllegalArgumentException("No embeddings");
    double[][] distances = new double[n][n];
    double maximum = 0;
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
      distances[i][j] = distances[j][i] = EmbeddingClusters.distance(vectors.get(i), vectors.get(j));
      maximum = Math.max(maximum, distances[i][j]);
    }
    if (maximum <= singleVoiceDistance || n == 1) return 1;
    if (n == 2) return 2;
    int winner = 1;
    double best = 0;
    for (int k = 2; k < n; k++) {
      int[] labels = EmbeddingClusters.cluster(vectors, k, 0);
      int[] sizes = new int[k];
      for (int label : labels) sizes[label]++;
      double score = 0;
      for (int i = 0; i < n; i++) {
        if (sizes[labels[i]] == 1) continue;
        double[] sums = new double[k];
        for (int j = 0; j < n; j++) sums[labels[j]] += distances[i][j];
        double a = sums[labels[i]] / (sizes[labels[i]] - 1);
        double b = Double.POSITIVE_INFINITY;
        for (int group = 0; group < k; group++)
          if (group != labels[i]) b = Math.min(b, sums[group] / sizes[group]);
        if (Math.max(a, b) > 0) score += (b - a) / Math.max(a, b);
      }
      score /= n;
      if (score > best) { best = score; winner = k; }
    }
    return winner;
  }
  public static int selectGap(List<double[]> vectors, double singleVoiceDistance) {
    int n = vectors.size();
    if (n == 0) throw new IllegalArgumentException("No embeddings");
    double[][] distances = new double[n][n];
    double maximum = 0;
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
      distances[i][j] = distances[j][i] = EmbeddingClusters.distance(vectors.get(i), vectors.get(j));
      maximum = Math.max(maximum, distances[i][j]);
    }
    if (maximum <= singleVoiceDistance || n == 1) return 1;
    double previous = 0, bestGap = 0;
    int winner = 1;
    for (int k = n; k >= 2; k--) {
      int[] labels = EmbeddingClusters.cluster(vectors, k, 0);
      double[][] sums = new double[k][k];
      int[] sizes = new int[k];
      for (int label : labels) sizes[label]++;
      for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
        if (labels[i] != labels[j]) {
          sums[labels[i]][labels[j]] += distances[i][j];
          sums[labels[j]][labels[i]] += distances[i][j];
        }
      double height = Double.POSITIVE_INFINITY;
      for (int i = 0; i < k; i++) for (int j = i + 1; j < k; j++)
        height = Math.min(height, sums[i][j] / (sizes[i] * sizes[j]));
      if (height - previous > bestGap) { bestGap = height - previous; winner = k; }
      previous = height;
    }
    return winner;
  }
}
