package dev.wordbyword.app.core.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.Callable;

public final class AndroidAudioPreparer implements AudioPreparer {
  private static final long DEQUEUE_TIMEOUT_US = 10_000;
  private static final String STAGE_OPENING = "Открываем аудиофайл";
  private static final String STAGE_DECODING = "Декодирование аудио";

  private final Context context;

  public AndroidAudioPreparer(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public PcmAudio prepare(URI source, ProcessingContext processingContext)
      throws ProcessingException {
    processingContext.reportProgress(STAGE_OPENING, OptionalDouble.empty());

    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    FilePcmAudio.Writer writer = null;
    boolean succeeded = false;
    try {
      wrap(
          "Не удалось открыть аудиофайл",
          () -> extractor.setDataSource(context, Uri.parse(source.toString()), null));

      int trackIndex = selectAudioTrack(extractor);
      MediaFormat trackFormat = extractor.getTrackFormat(trackIndex);
      extractor.selectTrack(trackIndex);
      OptionalLong durationUs = readDuration(trackFormat);

      String mime = trackFormat.getString(MediaFormat.KEY_MIME);
      codec =
          wrap(
              "Не удалось запустить декодер для формата " + mime,
              () -> {
                MediaCodec created = MediaCodec.createDecoderByType(mime);
                created.configure(trackFormat, null, null, 0);
                created.start();
                return created;
              });

      writer =
          wrap(
              "Не удалось создать временный файл для PCM",
              () -> new FilePcmAudio.Writer(context.getCacheDir()));

      decode(extractor, codec, writer, durationUs, processingContext);

      PcmAudio result = wrap("Не удалось завершить запись временного PCM-файла", writer::finish);
      succeeded = true;
      return result;
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (IllegalStateException ignored) {
        }
        codec.release();
      }
      extractor.release();
      if (writer != null && !succeeded) {
        writer.abort();
      }
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void wrap(String message, ThrowingRunnable action) throws ProcessingException {
    wrap(
        message,
        () -> {
          action.run();
          return null;
        });
  }

  private static <T> T wrap(String message, Callable<T> action) throws ProcessingException {
    try {
      return action.call();
    } catch (Exception exception) {
      throw new ProcessingException(message, exception);
    }
  }

  private int selectAudioTrack(MediaExtractor extractor) throws ProcessingException {
    for (int index = 0; index < extractor.getTrackCount(); index++) {
      MediaFormat format = extractor.getTrackFormat(index);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith("audio/")) {
        return index;
      }
    }
    throw new ProcessingException("В файле не найдена аудиодорожка");
  }

  private OptionalLong readDuration(MediaFormat format) {
    if (format.containsKey(MediaFormat.KEY_DURATION)) {
      long durationUs = format.getLong(MediaFormat.KEY_DURATION);
      if (durationUs > 0) {
        return OptionalLong.of(durationUs);
      }
    }
    return OptionalLong.empty();
  }

  private void decode(
      MediaExtractor extractor,
      MediaCodec codec,
      FilePcmAudio.Writer writer,
      OptionalLong durationUs,
      ProcessingContext processingContext)
      throws ProcessingException {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    LinearResampler resampler = null;
    LowPassFilter filter = null;
    boolean sawInputEos = false;
    boolean sawOutputEos = false;

    try {
      while (!sawOutputEos) {
        processingContext.throwIfCancelled();

        if (!sawInputEos) {
          int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
          if (inputIndex >= 0) {
            ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
            int sampleSize = inputBuffer == null ? -1 : extractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0) {
              codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              sawInputEos = true;
            } else {
              long presentationTimeUs = extractor.getSampleTime();
              codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
              extractor.advance();
              reportProgress(durationUs, presentationTimeUs, processingContext);
            }
          }
        }

        int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat outputFormat = codec.getOutputFormat();
          resampler = createResampler(outputFormat);
          filter = createAntiAliasingFilter(outputFormat);
        } else if (outputIndex >= 0) {
          if (resampler == null) {
            resampler = createResampler(codec.getOutputFormat());
            filter = createAntiAliasingFilter(codec.getOutputFormat());
          }
          if (info.size > 0) {
            ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
            writeDecodedChunk(outputBuffer, info, codec.getOutputFormat(), filter, resampler, writer);
          }
          codec.releaseOutputBuffer(outputIndex, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            sawOutputEos = true;
          }
        }
      }
    } catch (IOException exception) {
      throw new ProcessingException("Не удалось записать временный PCM-файл", exception);
    } catch (MediaCodec.CodecException exception) {
      throw new ProcessingException("Ошибка декодирования аудио", exception);
    }
  }

  private void reportProgress(
      OptionalLong durationUs, long positionUs, ProcessingContext processingContext) {
    if (durationUs.isPresent()) {
      double fraction = Math.min(1.0, positionUs / (double) durationUs.getAsLong());
      processingContext.reportProgress(STAGE_DECODING, OptionalDouble.of(fraction));
    } else {
      processingContext.reportProgress(STAGE_DECODING, OptionalDouble.empty());
    }
  }

  private LinearResampler createResampler(MediaFormat outputFormat) {
    int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
    return new LinearResampler(sampleRate, PcmAudio.SAMPLE_RATE_HZ);
  }

  private LowPassFilter createAntiAliasingFilter(MediaFormat outputFormat) {
    int sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
    if (sampleRate <= PcmAudio.SAMPLE_RATE_HZ) {
      return null;
    }
    return new LowPassFilter(sampleRate, PcmAudio.SAMPLE_RATE_HZ / 2, 63);
  }

  private void writeDecodedChunk(
      ByteBuffer outputBuffer,
      MediaCodec.BufferInfo info,
      MediaFormat outputFormat,
      LowPassFilter filter,
      LinearResampler resampler,
      FilePcmAudio.Writer writer)
      throws IOException {
    outputBuffer.position(info.offset);
    outputBuffer.limit(info.offset + info.size);
    ByteBuffer slice = outputBuffer.slice();
    slice.order(ByteOrder.LITTLE_ENDIAN);

    int channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    int pcmEncoding =
        outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            : AudioFormat.ENCODING_PCM_16BIT;

    float[] interleaved;
    if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
      FloatBuffer floatBuffer = slice.asFloatBuffer();
      interleaved = new float[floatBuffer.remaining()];
      floatBuffer.get(interleaved);
    } else {
      ShortBuffer shortBuffer = slice.asShortBuffer();
      interleaved = new float[shortBuffer.remaining()];
      for (int i = 0; i < interleaved.length; i++) {
        interleaved[i] = shortBuffer.get(i) / 32768f;
      }
    }

    float[] mono = toMono(interleaved, channelCount);
    float[] filtered = filter == null ? mono : filter.process(mono, 0, mono.length);
    float[] resampled = resampler.process(filtered, 0, filtered.length);
    writer.write(resampled, 0, resampled.length);
  }

  static float[] toMono(float[] interleaved, int channelCount) {
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
