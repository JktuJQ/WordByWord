package dev.wordbyword.app.core.audio;

public final class ChannelMixer {
  private ChannelMixer() {}

  public static float[] toMono(float[] interleaved, int channelCount) {
    if (channelCount <= 0) {
      throw new IllegalArgumentException("channelCount must be positive: " + channelCount);
    }
    if (interleaved.length % channelCount != 0) {
      throw new IllegalArgumentException(
          "interleaved length " + interleaved.length + " is not a multiple of " + channelCount);
    }
    if (channelCount == 1) {
      return interleaved.clone();
    }

    int frameCount = interleaved.length / channelCount;
    float[] mono = new float[frameCount];
    for (int frame = 0; frame < frameCount; frame++) {
      float sum = 0f;
      int base = frame * channelCount;
      for (int channel = 0; channel < channelCount; channel++) {
        sum += interleaved[base + channel];
      }
      mono[frame] = sum / channelCount;
    }
    return mono;
  }
}
