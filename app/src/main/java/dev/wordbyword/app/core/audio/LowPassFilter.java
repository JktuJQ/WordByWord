package dev.wordbyword.app.core.audio;

public final class LowPassFilter {
  private final float[] coefficients;
  private final float[] history;

  public LowPassFilter(int sampleRateHz, int cutoffHz, int tapCount) {
    if (tapCount < 3 || tapCount % 2 == 0) {
      throw new IllegalArgumentException("tapCount must be odd and >= 3: " + tapCount);
    }
    if (sampleRateHz <= 0 || cutoffHz <= 0 || cutoffHz * 2 >= sampleRateHz) {
      throw new IllegalArgumentException(
          "cutoffHz must be positive and below the Nyquist frequency: "
              + cutoffHz
              + " @ "
              + sampleRateHz
              + " Hz");
    }
    this.coefficients = windowedSincLowPass(sampleRateHz, cutoffHz, tapCount);
    this.history = new float[tapCount - 1];
  }

  public float[] process(float[] input, int offset, int length) {
    float[] output = new float[length];
    int historyLength = history.length;
    for (int i = 0; i < length; i++) {
      float sum = 0f;
      for (int tap = 0; tap < coefficients.length; tap++) {
        int sampleIndex = i - tap;
        float sample;
        if (sampleIndex >= 0) {
          sample = input[offset + sampleIndex];
        } else {
          int historyIndex = historyLength + sampleIndex;
          sample = historyIndex >= 0 ? history[historyIndex] : 0f;
        }
        sum += coefficients[tap] * sample;
      }
      output[i] = sum;
    }
    updateHistory(input, offset, length);
    return output;
  }

  private void updateHistory(float[] input, int offset, int length) {
    int historyLength = history.length;
    if (length >= historyLength) {
      System.arraycopy(input, offset + length - historyLength, history, 0, historyLength);
    } else {
      System.arraycopy(history, length, history, 0, historyLength - length);
      System.arraycopy(input, offset, history, historyLength - length, length);
    }
  }

  private static float[] windowedSincLowPass(int sampleRateHz, int cutoffHz, int tapCount) {
    float[] taps = new float[tapCount];
    double normalizedCutoff = cutoffHz / (double) sampleRateHz;
    int middle = tapCount / 2;
    double sum = 0;
    for (int i = 0; i < tapCount; i++) {
      int k = i - middle;
      double sinc =
          k == 0
              ? 2 * normalizedCutoff
              : Math.sin(2 * Math.PI * normalizedCutoff * k) / (Math.PI * k);
      double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (tapCount - 1));
      double tap = sinc * window;
      taps[i] = (float) tap;
      sum += tap;
    }
    for (int i = 0; i < tapCount; i++) {
      taps[i] = (float) (taps[i] / sum);
    }
    return taps;
  }
}
