package dev.wordbyword.app.core.audio;

import java.io.Closeable;
import java.io.IOException;
import java.util.OptionalLong;

public interface PcmAudio extends Closeable {
  int SAMPLE_RATE_HZ = 16000;

  OptionalLong sampleCount();

  Reader openReader() throws IOException;

  interface Reader extends Closeable {
    int read(float[] destination, int offset, int length) throws IOException;
  }
}
