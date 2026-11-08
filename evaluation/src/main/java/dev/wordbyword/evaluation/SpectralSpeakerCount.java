package dev.wordbyword.evaluation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class SpectralSpeakerCount {
  public record Eigen(double[] values, double[][] vectors) {}

  public static double[] eigenvalues(double[][] input) {
    return eigensystem(input).values();
  }

  public static Eigen eigensystem(double[][] input) {
    int n = input.length;
    double[][] a = new double[n][n];
    for (int i = 0; i < n; i++) a[i] = input[i].clone();
    double[][] v = new double[n][n];
    for (int i = 0; i < n; i++) v[i][i] = 1;
    for (int sweep = 0; sweep < 100; sweep++) {
      double largest = 0;
      for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
        largest = Math.max(largest, Math.abs(a[p][q]));
        if (Math.abs(a[p][q]) < 1e-12) continue;
        double angle = 0.5 * Math.atan2(2 * a[p][q], a[q][q] - a[p][p]);
        double c = Math.cos(angle), s = Math.sin(angle);
        double app = a[p][p], aqq = a[q][q], apq = a[p][q];
        for (int r = 0; r < n; r++) if (r != p && r != q) {
          double rp = a[r][p], rq = a[r][q];
          a[r][p] = a[p][r] = c * rp - s * rq;
          a[r][q] = a[q][r] = s * rp + c * rq;
        }
        for (int r = 0; r < n; r++) {
          double rp = v[r][p], rq = v[r][q];
          v[r][p] = c * rp - s * rq;
          v[r][q] = s * rp + c * rq;
        }
        a[p][p] = c*c*app - 2*s*c*apq + s*s*aqq;
        a[q][q] = s*s*app + 2*s*c*apq + c*c*aqq;
        a[p][q] = a[q][p] = 0;
      }
      if (largest < 1e-10) {
        double[] result = new double[n];
        for (int i = 0; i < n; i++) result[i] = a[i][i];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> result[i]));
        double[] sorted = new double[n];
        double[][] vectors = new double[n][n];
        for (int column = 0; column < n; column++) {
          sorted[column] = result[order[column]];
          for (int row = 0; row < n; row++) vectors[row][column] = v[row][order[column]];
        }
        return new Eigen(sorted, vectors);
      }
    }
    throw new IllegalStateException("Eigenvalue iteration did not converge");
  }

  public static int select(List<double[]> vectors, double neighborFraction) {
    int n = vectors.size();
    if (n == 0 || neighborFraction <= 0 || neighborFraction > 1)
      throw new IllegalArgumentException("Invalid spectral input");
    if (n == 1) return 1;
    double[][] distance = new double[n][n];
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
      distance[i][j] = distance[j][i] = EmbeddingClusters.distance(vectors.get(i), vectors.get(j));
    double maximum = 0;
    for (double[] row : distance) for (double value : row) maximum = Math.max(maximum, value);
    if (maximum < 1e-10) return 1;
    double[][] affinity = new double[n][n];
    int neighbors = Math.max(1, (int) Math.ceil((n - 1) * neighborFraction));
    for (int i = 0; i < n; i++) {
      final int row = i;
      Integer[] indices = new Integer[n - 1];
      int position = 0;
      for (int j = 0; j < n; j++) if (j != i) indices[position++] = j;
      Arrays.sort(indices, Comparator.comparingDouble(j -> distance[row][j]));
      for (int j = 0; j < neighbors; j++) {
        int column = indices[j];
        double weight = Math.max(0, 1 - distance[i][column]);
        affinity[i][column] += weight / 2;
        affinity[column][i] += weight / 2;
      }
    }
    double[] degree = new double[n];
    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) degree[i] += affinity[i][j];
    if (Arrays.stream(degree).allMatch(value -> value == 0)) return n;
    double[][] laplacian = new double[n][n];
    for (int i = 0; i < n; i++) {
      laplacian[i][i] = degree[i] == 0 ? 0 : 1;
      for (int j = 0; j < n; j++) if (degree[i] > 0 && degree[j] > 0)
        laplacian[i][j] -= affinity[i][j] / Math.sqrt(degree[i] * degree[j]);
    }
    double[] eigen = eigenvalues(laplacian);
    int count = 1;
    double gap = 0;
    for (int k = 1; k < n; k++)
      if (eigen[k] - eigen[k-1] > gap) { gap = eigen[k] - eigen[k-1]; count = k; }
    return count;
  }
}
