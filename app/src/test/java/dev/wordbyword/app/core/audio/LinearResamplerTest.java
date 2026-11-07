package dev.wordbyword.app.core.audio;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class LinearResamplerTest {
  private static final float DELTA = 1e-5f;

  @Test
  public void passthroughWhenRatesAreEqualAcrossMultipleChunks() {
    var resampler = new LinearResampler(16000, 16000);

    float[] first = resampler.process(new float[] {0.1f, 0.2f, 0.3f}, 0, 3);
    float[] second = resampler.process(new float[] {0.4f, 0.5f}, 0, 2);

    assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, first, DELTA);
    assertArrayEquals(new float[] {0.4f, 0.5f}, second, DELTA);
  }

  @Test
  public void downsamplesByExactIntegerRatio() {
    var resampler = new LinearResampler(32000, 16000);
    float[] input = {0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f};

    float[] output = resampler.process(input, 0, input.length);

    assertArrayEquals(new float[] {0f, 2f, 4f, 6f}, output, DELTA);
  }

  @Test
  public void upsamplesByExactIntegerRatioAndTruncatesSubPeriodTail() {
    var resampler = new LinearResampler(8000, 16000);
    float[] input = {0f, 10f, 20f, 30f};

    float[] output = resampler.process(input, 0, input.length);

    assertArrayEquals(new float[] {0f, 5f, 10f, 15f, 20f, 25f}, output, DELTA);
  }

  @Test
  public void outputIsIndependentOfHowInputIsSplitIntoChunks() {
    int length = 50;
    float[] input = new float[length];
    for (int i = 0; i < length; i++) {
      input[i] = i * 0.37f - 5f;
    }

    var singleChunkResampler = new LinearResampler(44100, 16000);
    float[] expected = singleChunkResampler.process(input, 0, length);

    var multiChunkResampler = new LinearResampler(44100, 16000);
    int[] chunkSizes = {1, 2, 3, 5, 7, 11, 13, 8};
    float[] actual = new float[0];
    int position = 0;
    for (int chunkSize : chunkSizes) {
      float[] produced = multiChunkResampler.process(input, position, chunkSize);
      float[] merged = new float[actual.length + produced.length];
      System.arraycopy(actual, 0, merged, 0, actual.length);
      System.arraycopy(produced, 0, merged, actual.length, produced.length);
      actual = merged;
      position += chunkSize;
    }

    assertArrayEquals(expected, actual, DELTA);
  }
}
