package dev.wordbyword.app.core.audio;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LowPassFilterTest {
  @Test
  public void passesLowFrequencyAndSuppressesHighFrequency() {
    int sampleRateHz = 44100;
    int cutoffHz = 8000;
    var filter = new LowPassFilter(sampleRateHz, cutoffHz, 63);

    float[] low = sine(1000, sampleRateHz, sampleRateHz);
    float[] high = sine(14000, sampleRateHz, sampleRateHz);

    float lowRmsBefore = rms(low, 0, low.length);
    float highRmsBefore = rms(high, 0, high.length);
    float lowRmsAfter = rms(filter.process(low, 0, low.length), sampleRateHz / 10, low.length - sampleRateHz / 10);
    var highFilter = new LowPassFilter(sampleRateHz, cutoffHz, 63);
    float highRmsAfter =
        rms(
            highFilter.process(high, 0, high.length),
            sampleRateHz / 10,
            high.length - sampleRateHz / 10);

    assertTrue("low-frequency tone should pass through nearly unattenuated", lowRmsAfter > lowRmsBefore * 0.9f);
    assertTrue("high-frequency tone should be suppressed", highRmsAfter < highRmsBefore * 0.1f);
  }

  @Test
  public void appliesAcrossChunkBoundariesWithoutDiscontinuity() {
    int sampleRateHz = 44100;
    var wholeFilter = new LowPassFilter(sampleRateHz, 8000, 63);
    var chunkedFilter = new LowPassFilter(sampleRateHz, 8000, 63);

    float[] signal = sine(1000, sampleRateHz, sampleRateHz / 10);
    float[] whole = wholeFilter.process(signal, 0, signal.length);

    float[] chunked = new float[signal.length];
    int chunkSize = 500;
    for (int offset = 0; offset < signal.length; offset += chunkSize) {
      int length = Math.min(chunkSize, signal.length - offset);
      float[] out = chunkedFilter.process(signal, offset, length);
      System.arraycopy(out, 0, chunked, offset, length);
    }

    for (int i = 0; i < signal.length; i++) {
      assertTrue(
          "chunked and whole-buffer filtering must match at sample " + i,
          Math.abs(whole[i] - chunked[i]) < 1e-5f);
    }
  }

  @Test
  public void rejectsEvenTapCountAndCutoffAboveNyquist() {
    assertThrows(IllegalArgumentException.class, () -> new LowPassFilter(44100, 8000, 64));
    assertThrows(IllegalArgumentException.class, () -> new LowPassFilter(16000, 9000, 63));
  }

  private static float[] sine(int frequencyHz, int sampleRateHz, int sampleCount) {
    float[] samples = new float[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
      samples[i] = (float) Math.sin(2 * Math.PI * frequencyHz * i / sampleRateHz);
    }
    return samples;
  }

  private static float rms(float[] samples, int offset, int length) {
    double sumSquares = 0;
    for (int i = offset; i < offset + length; i++) {
      sumSquares += (double) samples[i] * samples[i];
    }
    return (float) Math.sqrt(sumSquares / length);
  }
}
