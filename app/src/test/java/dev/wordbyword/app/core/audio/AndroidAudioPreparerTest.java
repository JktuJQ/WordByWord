package dev.wordbyword.app.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AndroidAudioPreparerTest {
  private static final float DELTA = 1e-6f;

  @Test
  public void monoPassthroughIsIdentity() {
    float[] mono = AndroidAudioPreparer.toMono(new float[] {0.1f, -0.2f, 0.3f}, 1);

    assertArrayEquals(new float[] {0.1f, -0.2f, 0.3f}, mono, DELTA);
  }

  @Test
  public void stereoAveragesLeftAndRight() {
    float[] mono = AndroidAudioPreparer.toMono(new float[] {1.0f, -1.0f, 0.5f, 0.5f}, 2);

    assertArrayEquals(new float[] {0.0f, 0.5f}, mono, DELTA);
  }

  @Test
  public void threeChannelsAverageAllChannelsEqually() {
    float[] mono = AndroidAudioPreparer.toMono(new float[] {1f, 2f, 3f, 4f, 5f, 6f}, 3);

    assertArrayEquals(new float[] {2f, 5f}, mono, DELTA);
  }

  @Test
  public void emptyInputProducesEmptyOutput() {
    float[] mono = AndroidAudioPreparer.toMono(new float[0], 2);

    assertArrayEquals(new float[0], mono, DELTA);
  }

  @Test
  public void nonPositiveChannelCountIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> AndroidAudioPreparer.toMono(new float[] {1f}, 0));
  }

  @Test
  public void lengthNotDivisibleByChannelCountIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AndroidAudioPreparer.toMono(new float[] {1f, 2f, 3f}, 2));
  }
}
