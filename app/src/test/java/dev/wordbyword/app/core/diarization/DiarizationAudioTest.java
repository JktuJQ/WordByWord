package dev.wordbyword.app.core.diarization;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.io.IOException;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class DiarizationAudioTest {
  private static final class Audio implements PcmAudio, ProcessingContext {
    final float[] samples;
    final OptionalLong count;
    float[] firstBuffer;
    boolean closed;
    boolean cancelled;
    boolean cancelOnRead;
    boolean stall;
    boolean fail;
    int position;

    Audio(float[] samples, OptionalLong count) {
      this.samples = samples;
      this.count = count;
    }

    public OptionalLong sampleCount() {
      return count;
    }

    public boolean isCancellationRequested() {
      return cancelled;
    }

    public void reportProgress(String stage, OptionalDouble fraction) {}

    public void close() {}

    public Reader openReader() {
      return new Reader() {
        public int read(float[] destination, int offset, int length) throws IOException {
          if (firstBuffer == null) firstBuffer = destination;
          if (fail) throw new IOException("Read failed");
          if (stall) return 0;
          if (position == samples.length) return -1;
          int read = Math.min(length, samples.length - position);
          System.arraycopy(samples, position, destination, offset, read);
          position += read;
          if (cancelOnRead) cancelled = true;
          return read;
        }

        public void close() {
          closed = true;
        }
      };
    }
  }

  @Test
  public void exactKnownSizeReturnsOriginalBufferWithoutGrowingAtEof() throws Exception {
    var audio = new Audio(new float[] {1, 2, 3}, OptionalLong.of(3));
    var samples = SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio);
    assertArrayEquals(audio.samples, samples, 0f);
    assertSame(audio.firstBuffer, samples);
    assertTrue(audio.closed);
  }

  @Test
  public void unknownSizeGrowsWithoutLosingSamples() throws Exception {
    float[] samples = new float[PcmAudio.SAMPLE_RATE_HZ + 13];
    for (int i = 0; i < samples.length; i++) samples[i] = i;
    var audio = new Audio(samples, OptionalLong.empty());
    assertArrayEquals(samples, SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio), 0f);
    assertTrue(audio.closed);
  }

  @Test
  public void emptyAndOverestimatedSizesReturnOnlyActualSamples() throws Exception {
    var empty = new Audio(new float[0], OptionalLong.of(0));
    assertEquals(0, SherpaOnnxSpeakerDiarizer.readAllSamples(empty, empty).length);
    var shorter = new Audio(new float[] {1}, OptionalLong.of(3));
    assertArrayEquals(
        shorter.samples, SherpaOnnxSpeakerDiarizer.readAllSamples(shorter, shorter), 0f);
  }

  @Test
  public void invalidCountFailsBeforeOpeningReader() {
    for (long count : new long[] {-1, Long.MAX_VALUE}) {
      var audio = new Audio(new float[0], OptionalLong.of(count));
      assertThrows(
          ProcessingException.class, () -> SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio));
      assertNull(audio.firstBuffer);
    }
  }

  @Test
  public void cancellationDuringReadingClosesReader() {
    var audio = new Audio(new float[] {1, 2}, OptionalLong.of(2));
    audio.cancelOnRead = true;
    assertThrows(
        CancellationException.class, () -> SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio));
    assertTrue(audio.closed);
  }

  @Test
  public void cancelledInputIsNeverOpened() {
    var audio = new Audio(new float[] {1}, OptionalLong.of(1));
    audio.cancelled = true;
    assertThrows(
        CancellationException.class, () -> SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio));
    assertNull(audio.firstBuffer);
  }

  @Test
  public void zeroReadAndIoFailureCloseReaderAndPreserveCause() {
    for (boolean stall : new boolean[] {true, false}) {
      var audio = new Audio(new float[] {1}, OptionalLong.of(1));
      audio.stall = stall;
      audio.fail = !stall;
      var exception =
          assertThrows(
              ProcessingException.class,
              () -> SherpaOnnxSpeakerDiarizer.readAllSamples(audio, audio));
      assertTrue(exception.getCause() instanceof IOException);
      assertTrue(audio.closed);
    }
  }
}
