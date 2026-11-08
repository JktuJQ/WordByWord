package dev.wordbyword.evaluation;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;

public class SpectralClusteringTest {
  @Test
  public void fullGroupingPreservesSeparatedVoices() {
    var vectors = new ArrayList<double[]>();
    for (int voice = 0; voice < 3; voice++) for (int repeat = 0; repeat < 4; repeat++) {
      double[] vector = new double[3]; vector[voice] = 1; vectors.add(vector);
    }
    int[] labels = SpectralClustering.cluster(vectors, 0.2);
    for (int i = 0; i < 12; i++) for (int j = 0; j < 12; j++)
      assertEquals(i / 4 == j / 4, labels[i] == labels[j]);
  }

  @Test
  public void eigenvectorsSatisfyOriginalMatrix() {
    double[][] a = {{2, 1}, {1, 2}};
    var e = SpectralSpeakerCount.eigensystem(a);
    for (int c = 0; c < 2; c++) for (int r = 0; r < 2; r++)
      assertEquals(e.values()[c] * e.vectors()[r][c], a[r][0] * e.vectors()[0][c] + a[r][1] * e.vectors()[1][c], 1e-9);
  }
}
