package dev.wordbyword.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EmbeddingClusters {
  public static double[] normalize(double[] vector) {
    double norm = 0;
    for (double value : vector) {
      if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite embedding");
      norm += value * value;
    }
    if (norm < 1e-20) throw new IllegalArgumentException("Zero embedding");
    double length = Math.sqrt(norm);
    return Arrays.stream(vector).map(value -> value / length).toArray();
  }

  public static double distance(double[] a, double[] b) {
    if (a.length != b.length) throw new IllegalArgumentException("Embedding dimensions differ");
    double dot = 0;
    for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
    return Math.max(0, Math.min(2, 1 - dot));
  }

  public static int[] cluster(List<double[]> vectors, int count, double threshold) {
    if (count == 0 || count < -1 || count > vectors.size())
      throw new IllegalArgumentException("Invalid requested cluster count");
    int n = vectors.size();
    double[][] distances = new double[n][n];
    for (int i = 0; i < n; i++)
      for (int j = i + 1; j < n; j++)
        distances[i][j] = distances[j][i] = distance(vectors.get(i), vectors.get(j));
    var groups = new ArrayList<List<Integer>>();
    for (int i = 0; i < n; i++) groups.add(new ArrayList<>(List.of(i)));
    while (groups.size() > Math.max(1, count)) {
      int left = -1, right = -1;
      double best = Double.POSITIVE_INFINITY;
      for (int i = 0; i < groups.size(); i++) {
        for (int j = i + 1; j < groups.size(); j++) {
          double sum = 0;
          for (int a : groups.get(i)) for (int b : groups.get(j)) sum += distances[a][b];
          double mean = sum / (groups.get(i).size() * groups.get(j).size());
          if (mean < best) { best = mean; left = i; right = j; }
        }
      }
      if (count == -1 && best > threshold) break;
      groups.get(left).addAll(groups.get(right));
      groups.remove(right);
    }
    int[] labels = new int[n];
    for (int i = 0; i < groups.size(); i++) for (int item : groups.get(i)) labels[item] = i;
    return labels;
  }

  public static double[] centroid(List<double[]> vectors) {
    if (vectors.isEmpty()) throw new IllegalArgumentException("No centroid members");
    double[] mean = new double[vectors.get(0).length];
    for (var vector : vectors) {
      if (vector.length != mean.length) throw new IllegalArgumentException("Embedding dimensions differ");
      for (int j = 0; j < mean.length; j++) mean[j] += vector[j];
    }
    return normalize(mean);
  }
}
