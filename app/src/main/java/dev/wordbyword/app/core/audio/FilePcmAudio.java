package dev.wordbyword.app.core.audio;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.OptionalLong;

final class FilePcmAudio implements PcmAudio {
  private final File file;
  private final long sampleCount;

  FilePcmAudio(File file, long sampleCount) {
    this.file = file;
    this.sampleCount = sampleCount;
  }

  @Override
  public OptionalLong sampleCount() {
    return OptionalLong.of(sampleCount);
  }

  @Override
  public Reader openReader() throws IOException {
    return new FileReader(file);
  }

  @Override
  public void close() throws IOException {
    Files.deleteIfExists(file.toPath());
  }

  private static final class FileReader implements Reader {
    private final InputStream in;

    FileReader(File file) throws IOException {
      this.in = new FileInputStream(file);
    }

    @Override
    public int read(float[] destination, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      byte[] buffer = new byte[length * 4];
      int totalBytesRead = 0;
      while (totalBytesRead < buffer.length) {
        int bytesRead = in.read(buffer, totalBytesRead, buffer.length - totalBytesRead);
        if (bytesRead == -1) {
          break;
        }
        totalBytesRead += bytesRead;
      }
      if (totalBytesRead == 0) {
        return -1;
      }

      int samplesRead = totalBytesRead / 4;
      ByteBuffer.wrap(buffer, 0, samplesRead * 4)
          .order(ByteOrder.LITTLE_ENDIAN)
          .asFloatBuffer()
          .get(destination, offset, samplesRead);
      return samplesRead;
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  static final class Writer implements Closeable {
    private final File file;
    private final OutputStream out;
    private long sampleCount;
    private boolean finished;

    Writer(File directory) throws IOException {
      this.file = File.createTempFile("audio-prep-", ".pcm", directory);
      this.out = new BufferedOutputStream(new FileOutputStream(file));
    }

    void write(float[] data, int offset, int length) throws IOException {
      if (length == 0) {
        return;
      }
      ByteBuffer byteBuffer = ByteBuffer.allocate(length * 4).order(ByteOrder.LITTLE_ENDIAN);
      byteBuffer.asFloatBuffer().put(data, offset, length);
      out.write(byteBuffer.array());
      sampleCount += length;
    }

    FilePcmAudio finish() throws IOException {
      out.close();
      finished = true;
      return new FilePcmAudio(file, sampleCount);
    }

    void abort() {
      try {
        out.close();
      } catch (IOException ignored) {
      }
      file.delete();
    }

    @Override
    public void close() throws IOException {
      if (!finished) {
        abort();
      }
    }
  }
}
