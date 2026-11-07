package dev.wordbyword.app.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilePcmAudioTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final float DELTA = 1e-6f;

  private FilePcmAudio write(float[] samples) throws IOException {
    File directory = temporary.newFolder();
    try (var writer = new FilePcmAudio.Writer(directory)) {
      writer.write(samples, 0, samples.length);
      return writer.finish();
    }
  }

  @Test
  public void roundTripsWrittenSamplesExactly() throws IOException {
    float[] samples = {0.1f, -0.2f, 0.3f, -0.4f, 0.5f};
    FilePcmAudio audio = write(samples);

    assertEquals(samples.length, audio.sampleCount().getAsLong());
    try (PcmAudio.Reader reader = audio.openReader()) {
      float[] destination = new float[samples.length];
      int read = reader.read(destination, 0, destination.length);

      assertEquals(samples.length, read);
      for (int i = 0; i < samples.length; i++) {
        assertEquals(samples[i], destination[i], DELTA);
      }
      assertEquals(-1, reader.read(new float[1], 0, 1));
    }
    audio.close();
  }

  @Test
  public void independentReadersEachStartFromSampleZero() throws IOException {
    float[] samples = {1f, 2f, 3f, 4f, 5f};
    FilePcmAudio audio = write(samples);

    try (PcmAudio.Reader readerA = audio.openReader();
        PcmAudio.Reader readerB = audio.openReader()) {
      float[] fromA = new float[2];
      assertEquals(2, readerA.read(fromA, 0, 2));
      assertEquals(1f, fromA[0], DELTA);
      assertEquals(2f, fromA[1], DELTA);

      float[] fromB = new float[samples.length];
      assertEquals(samples.length, readerB.read(fromB, 0, samples.length));
      assertEquals(1f, fromB[0], DELTA);
      assertEquals(5f, fromB[4], DELTA);
    }
    audio.close();
  }

  @Test
  public void readReturnsPositiveCountThenMinusOneAtEof() throws IOException {
    float[] samples = {1f, 2f, 3f};
    FilePcmAudio audio = write(samples);

    try (PcmAudio.Reader reader = audio.openReader()) {
      int read = reader.read(new float[10], 0, 10);
      assertEquals(3, read);
      assertEquals(-1, reader.read(new float[1], 0, 1));
    }
    audio.close();
  }

  @Test
  public void zeroLengthReadReturnsZeroRegardlessOfPosition() throws IOException {
    float[] samples = {1f, 2f, 3f};
    FilePcmAudio audio = write(samples);

    try (PcmAudio.Reader reader = audio.openReader()) {
      assertEquals(0, reader.read(new float[0], 0, 0));
      reader.read(new float[10], 0, 10);
      assertEquals(0, reader.read(new float[0], 0, 0));
    }
    audio.close();
  }

  @Test
  public void closeDeletesBackingFileAndIsIdempotent() throws IOException {
    FilePcmAudio audio = write(new float[] {1f, 2f});

    audio.close();
    assertThrows(IOException.class, audio::openReader);

    audio.close();
  }

  @Test
  public void emptyWriteProducesZeroSampleCount() throws IOException {
    FilePcmAudio audio = write(new float[0]);

    assertEquals(0L, audio.sampleCount().getAsLong());
    try (PcmAudio.Reader reader = audio.openReader()) {
      assertTrue(reader.read(new float[1], 0, 1) == -1);
    }
    audio.close();
  }
}
