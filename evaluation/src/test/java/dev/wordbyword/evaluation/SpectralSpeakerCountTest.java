package dev.wordbyword.evaluation;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;

public class SpectralSpeakerCountTest {
  @Test
  public void eigenvaluesMatchKnownSymmetricMatrix() {
    assertArrayEquals(new double[] {1, 3}, SpectralSpeakerCount.eigenvalues(new double[][] {{2, 1}, {1, 2}}), 1e-9);
  }

  @Test
  public void disconnectedVoiceGroupsHaveNoFourSpeakerCeiling() {
    for (int k : new int[] {1, 2, 3, 4, 7}) {
      var vectors = new ArrayList<double[]>();
      for (int i = 0; i < k; i++) for (int repeat = 0; repeat < 4; repeat++) {
        double[] vector = new double[k]; vector[i] = 1; vectors.add(vector);
      }
      assertEquals(k, SpectralSpeakerCount.select(vectors, 0.2));
    }
  }
  @Test
  public void isolatedVerticesRemainDistinct() {
    assertEquals(2, SpectralSpeakerCount.select(java.util.List.of(new double[] {1, 0}, new double[] {0, 1}), 0.2));
  }

  @Test
  public void tridiagonalSpectrumMatchesAnalyticValues() {
    double[][] matrix = new double[5][5];
    double[] expected = new double[5];
    for (int i = 0; i < 5; i++) {
      matrix[i][i] = 2;
      if (i > 0) matrix[i][i-1] = matrix[i-1][i] = -1;
      expected[i] = 2 - 2 * Math.cos((i + 1) * Math.PI / 6);
    }
    assertArrayEquals(expected, SpectralSpeakerCount.eigenvalues(matrix), 1e-9);
  }
}
