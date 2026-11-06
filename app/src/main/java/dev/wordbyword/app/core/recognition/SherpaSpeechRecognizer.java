package dev.wordbyword.app.core.recognition;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

public final class SherpaSpeechRecognizer implements SpeechRecognizer {
  private static final int RATE = PcmAudio.SAMPLE_RATE_HZ;
  private final File models;

  public SherpaSpeechRecognizer(File models) {
    this.models = models;
  }

  @Override
  public RecognitionResult transcribe(PcmAudio audio, ProcessingContext context)
      throws ProcessingException {
    try {
      context.throwIfCancelled();
      List<SpeechInterval> windows = detectSpeech(audio, context);
      return windows.isEmpty()
          ? new RecognitionResult("", List.of(), RecognitionResult.Timing.SEGMENT)
          : transcribeSpeech(audio, windows, context);
    } catch (CancellationException exception) {
      throw exception;
    } catch (IOException | RuntimeException | LinkageError exception) {
      throw new ProcessingException(
          "Не удалось распознать аудио: " + exception.getMessage(), exception);
    }
  }

  private List<SpeechInterval> detectSpeech(PcmAudio audio, ProcessingContext context)
      throws IOException {
    context.reportProgress("Ищем речь", OptionalDouble.empty());
    var windows = new ArrayList<SpeechInterval>();
    long count;
    var silero = new SileroVadModelConfig();
    silero.setModel(model("silero_vad.onnx"));
    silero.setMaxSpeechDuration(15f);
    var vadConfig = new VadModelConfig();
    vadConfig.setSileroVadModelConfig(silero);
    Vad vad = new Vad(null, vadConfig);
    try (var reader = audio.openReader()) {
      count = 0;
      long nextProgress = 0;
      long progressStep = Math.max(RATE, audio.sampleCount().orElse(0) / 100);
      float[] buffer = new float[512];
      while (true) {
        context.throwIfCancelled();
        int n = readSamples(reader, buffer, context);
        if (n == 0) break;
        count += n;
        if (n < buffer.length) Arrays.fill(buffer, n, buffer.length, 0f);
        vad.acceptWaveform(buffer);
        collectSpeechIntervals(vad, windows);
        if (count >= nextProgress
            && audio.sampleCount().isPresent()
            && audio.sampleCount().getAsLong() > 0) {
          context.reportProgress(
              "Ищем речь",
              OptionalDouble.of(Math.min(1, count / (double) audio.sampleCount().getAsLong())));
          nextProgress = count + progressStep;
        }
      }
      vad.flush();
      collectSpeechIntervals(vad, windows);
    } finally {
      vad.release();
    }
    context.throwIfCancelled();
    return addContext(windows, count);
  }

  private OfflineRecognizer loadRecognizer() throws IOException {
    var transducer = new OfflineTransducerModelConfig();
    transducer.setEncoder(model("encoder.int8.onnx"));
    transducer.setDecoder(model("decoder.onnx"));
    transducer.setJoiner(model("joiner.onnx"));
    var modelConfig = new OfflineModelConfig();
    modelConfig.setTransducer(transducer);
    modelConfig.setTokens(model("tokens.txt"));
    modelConfig.setModelType("nemo_transducer");
    modelConfig.setNumThreads(2);
    var config = new OfflineRecognizerConfig();
    config.setModelConfig(modelConfig);
    return new OfflineRecognizer(null, config);
  }

  private RecognitionResult transcribeSpeech(
      PcmAudio audio, List<SpeechInterval> padded, ProcessingContext context) throws IOException {
    context.reportProgress("Загружаем распознавание", OptionalDouble.empty());
    OfflineRecognizer recognizer = loadRecognizer();
    try (var reader = audio.openReader()) {
      var result = new ArrayList<RecognitionResult.Segment>();
      long position = 0;
      float[] skip = new float[RATE];
      for (int i = 0; i < padded.size(); i++) {
        context.throwIfCancelled();
        SpeechInterval window = padded.get(i);
        while (position < window.start()) {
          int n = reader.read(skip, 0, (int) Math.min(skip.length, window.start() - position));
          if (n < 1) throw new IOException("PCM ended before speech window");
          position += n;
          context.throwIfCancelled();
        }
        float[] samples = new float[Math.toIntExact(window.end() - window.start())];
        if (readSamples(reader, samples, context) != samples.length)
          throw new IOException("Truncated PCM");
        position += samples.length;
        context.reportProgress("Распознаём речь", OptionalDouble.of(i / (double) padded.size()));
        OfflineStream stream = recognizer.createStream();
        try {
          stream.acceptWaveform(samples, RATE);
          context.throwIfCancelled();
          recognizer.decode(stream);
          context.throwIfCancelled();
          String text = recognizer.getResult(stream).getText().trim();
          if (!text.isEmpty())
            result.add(
                new RecognitionResult.Segment(
                    text, window.start() * 1000 / RATE, window.end() * 1000 / RATE));
        } finally {
          stream.release();
        }
      }
      context.throwIfCancelled();
      context.reportProgress("Распознаём речь", OptionalDouble.of(1));
      String text =
          String.join(
              " ",
              result.stream()
                  .map(RecognitionResult.Segment::text)
                  .collect(java.util.stream.Collectors.toList()));
      return new RecognitionResult(text, result, RecognitionResult.Timing.SEGMENT);
    } finally {
      recognizer.release();
    }
  }

  private String model(String name) throws IOException {
    File file = new File(models, name);
    if (!file.isFile() || file.length() == 0) {
      throw new IOException(
          "Модели распознавания не установлены. Установите комплект моделей приложения.");
    }
    return file.getAbsolutePath();
  }

  private static int readSamples(PcmAudio.Reader reader, float[] samples, ProcessingContext context)
      throws IOException {
    int offset = 0;
    while (offset < samples.length) {
      context.throwIfCancelled();
      int n = reader.read(samples, offset, Math.min(RATE, samples.length - offset));
      if (n == -1) break;
      if (n == 0) throw new IOException("PCM reader returned zero samples");
      offset += n;
    }
    return offset;
  }

  private static void collectSpeechIntervals(Vad vad, List<SpeechInterval> windows) {
    while (!vad.empty()) {
      var segment = vad.front();
      windows.add(
          new SpeechInterval(segment.getStart(), segment.getStart() + segment.getSamples().length));
      vad.pop();
    }
  }

  record SpeechInterval(long start, long end) {}

  static List<SpeechInterval> addContext(List<SpeechInterval> windows, long count) {
    var result = new ArrayList<SpeechInterval>();
    for (int i = 0; i < windows.size(); i++) {
      SpeechInterval w = windows.get(i);
      long start = Math.max(0, w.start() - RATE / 5);
      long end = Math.min(count, w.end() + RATE / 5);
      if (i > 0) start = Math.max(start, (windows.get(i - 1).end() + w.start()) / 2);
      if (i + 1 < windows.size()) end = Math.min(end, (w.end() + windows.get(i + 1).start()) / 2);
      if (start < end) result.add(new SpeechInterval(start, end));
    }
    return result;
  }
}
