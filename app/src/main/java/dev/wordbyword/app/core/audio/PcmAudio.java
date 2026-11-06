package dev.wordbyword.app.core.audio;

import java.io.Closeable;
import java.io.IOException;
import java.util.OptionalLong;

/**
 * Prepared audio: mono, 16 kHz, normalized float samples in [-1, 1].
 *
 * <p>Samples retain the original timeline, including silence. Each reader starts at sample zero.
 * Storage may be a file, memory, or another repeatable source; consumers need not load it all at
 * once. The caller closes every reader, then the audio. Closing audio releases owned temporary
 * resources, never the original recording. Readers are not required to be thread-safe.
 */
public interface PcmAudio extends Closeable {
  int SAMPLE_RATE_HZ = 16000;
  int CHANNEL_COUNT = 1;

  /** Total samples if known; otherwise empty. A sample is also a frame because audio is mono. */
  OptionalLong sampleCount();

  Reader openReader() throws IOException;

  interface Reader extends Closeable {
    /**
     * Reads up to length samples into destination at offset. Returns -1 at EOF, 0 only for
     * length=0, and a positive count otherwise. Implementations check array bounds.
     */
    int read(float[] destination, int offset, int length) throws IOException;
  }
}
