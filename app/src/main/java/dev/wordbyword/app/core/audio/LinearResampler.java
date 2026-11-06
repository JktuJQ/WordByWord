package dev.wordbyword.app.core.audio;

import java.util.Arrays;

public final class LinearResampler {
  private final int sourceRateHz;
  private final int targetRateHz;
  private final boolean passthrough;

  private long sourceSamplesSeen;
  private long outputSamplesProduced;
  private float previousSample;

  public LinearResampler(int sourceRateHz, int targetRateHz) {
    if (sourceRateHz <= 0 || targetRateHz <= 0) {
      throw new IllegalArgumentException(
          "Sample rates must be positive: " + sourceRateHz + " -> " + targetRateHz);
    }
    this.sourceRateHz = sourceRateHz;
    this.targetRateHz = targetRateHz;
    this.passthrough = sourceRateHz == targetRateHz;
  }

  public float[] process(float[] monoChunk, int offset, int length) {
    if (length == 0) {
      return new float[0];
    }
    if (passthrough) {
      float[] copy = Arrays.copyOfRange(monoChunk, offset, offset + length);
      sourceSamplesSeen += length;
      outputSamplesProduced += length;
      previousSample = monoChunk[offset + length - 1];
      return copy;
    }

    long chunkStartIndex = sourceSamplesSeen;
    long chunkEndIndex = chunkStartIndex + length - 1;

    int estimatedCapacity = (int) ((long) length * targetRateHz / sourceRateHz) + 2;
    float[] output = new float[estimatedCapacity];
    int produced = 0;

    long k = outputSamplesProduced;
    while (true) {
      double sourcePosition = (double) k * sourceRateHz / targetRateHz;
      long floorIndex = (long) Math.floor(sourcePosition);
      if (floorIndex + 1 > chunkEndIndex) {
        break;
      }

      float leftSample =
          floorIndex == chunkStartIndex - 1
              ? previousSample
              : monoChunk[offset + (int) (floorIndex - chunkStartIndex)];
      float rightSample = monoChunk[offset + (int) (floorIndex + 1 - chunkStartIndex)];
      float fraction = (float) (sourcePosition - floorIndex);

      if (produced == output.length) {
        output = Arrays.copyOf(output, output.length * 2 + 1);
      }
      output[produced++] = leftSample + (rightSample - leftSample) * fraction;
      k++;
    }

    sourceSamplesSeen = chunkEndIndex + 1;
    outputSamplesProduced = k;
    previousSample = monoChunk[offset + length - 1];

    return Arrays.copyOf(output, produced);
  }
}
