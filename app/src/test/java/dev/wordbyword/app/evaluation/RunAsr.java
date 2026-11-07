package dev.wordbyword.app.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.recognition.SherpaSpeechRecognizer;
import dev.wordbyword.evaluation.ScoreAsr;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class RunAsr {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final ProcessingContext CONTEXT =
      new ProcessingContext() {
        public boolean isCancellationRequested() {
          return Thread.currentThread().isInterrupted();
        }

        public void reportProgress(String stage, OptionalDouble fraction) {}
      };

  public static void main(String[] args) throws Exception {
    if (args.length != 6 && args.length != 8)
      throw new IllegalArgumentException(
          "Usage: RunAsr manifest.json pcm-directory model-directory pipeline|transducer|whisper"
              + " partition|all output.json [max-speech-seconds context-ms]");
    Path manifestPath = Path.of(args[0]);
    var manifest =
        JSON.fromJson(
            new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8), JsonObject.class);
    Path pcm = Path.of(args[1]), models = Path.of(args[2]), output = Path.of(args[5]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    var config = args[3].equals("pipeline") ? null : configure(models, args[3]);
    float maxSpeechDuration = args.length == 8 ? Float.parseFloat(args[6]) : 15f;
    int contextMs = args.length == 8 ? Integer.parseInt(args[7]) : 200;
    var recognizer =
        new SherpaSpeechRecognizer(models.toFile(), config, maxSpeechDuration, contextMs);
    var report = new JsonObject();
    report.addProperty(
        "platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.addProperty("java", System.getProperty("java.version"));
    report.addProperty("runtime", "sherpa-onnx 2.0.1 / CPU / ASR threads=2");
    report.addProperty("model_directory", models.toString());
    report.add("model_sha256", modelHashes(models, config));
    report.addProperty("family", args[3]);
    report.addProperty("partition", args[4]);
    report.addProperty("vad_max_speech_seconds", maxSpeechDuration);
    report.addProperty("vad_context_ms", contextMs);
    report.addProperty(
        "manifest_sha256",
        hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifestPath))));
    report.addProperty(
        "preparation",
        "external PCM16 LE, mono, 16000 Hz; includes VAD and per-file ASR model loading; not"
            + " AndroidAudioPreparer");
    var runs = new JsonArray();
    report.add("runs", runs);
    for (var element : manifest.getAsJsonArray("samples")) {
      var sample = element.getAsJsonObject();
      if (!args[4].equals("all")
          && (!sample.has("partition") || !sample.get("partition").getAsString().equals(args[4])))
        continue;
      String name = sample.get("file").getAsString();
      Path path = pcm.resolve(name.replaceFirst("\\.wav$", ".pcm"));
      var run = new JsonObject();
      run.addProperty("file", name);
      long started = System.nanoTime();
      try (var audio = new Audio(path)) {
        var result = recognizer.transcribe(audio, CONTEXT);
        run.addProperty("status", "READY");
        run.addProperty("hypothesis", result.text());
        run.add("segments", JSON.toJsonTree(result.segments()));
        run.addProperty("seconds", audio.sampleCount().orElseThrow() / 16000.0);
      } catch (Exception exception) {
        run.addProperty("status", "ERROR");
        run.addProperty("error", exception.toString());
        throw exception;
      } finally {
        run.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0);
        runs.add(run);
        Files.write(output, JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
      }
      System.out.printf("%d %s %.0f ms%n", runs.size(), name, run.get("elapsed_ms").getAsDouble());
    }
    if (runs.isEmpty()) throw new IllegalArgumentException("No samples selected");
    var metrics =
        args[4].equals("all")
            ? ScoreAsr.score(manifest, report)
            : ScoreAsr.scorePartition(manifest, report, args[4]);
    double seconds = 0, elapsedMs = 0;
    for (var element : runs) {
      seconds += element.getAsJsonObject().get("seconds").getAsDouble();
      elapsedMs += element.getAsJsonObject().get("elapsed_ms").getAsDouble();
    }
    metrics.remove("samples");
    metrics.addProperty("audio_seconds", seconds);
    metrics.addProperty("processing_seconds", elapsedMs / 1000);
    metrics.addProperty("rtf", elapsedMs / (seconds * 1000));
    report.add("metrics", metrics);
    Files.write(output, JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
    System.out.printf(
        "WER %.2f%%; CER %.2f%%; RTF %.3f%n",
        metrics.get("wer").getAsDouble() * 100,
        metrics.get("cer").getAsDouble() * 100,
        metrics.get("rtf").getAsDouble());
  }

  private static OfflineModelConfig configure(Path directory, String family) throws IOException {
    var config = new OfflineModelConfig();
    config.setNumThreads(2);
    if (family.equals("transducer")) {
      var transducer = new OfflineTransducerModelConfig();
      transducer.setEncoder(find(directory, "encoder.int8.onnx"));
      transducer.setDecoder(find(directory, "decoder.int8.onnx", "decoder.onnx"));
      transducer.setJoiner(find(directory, "joiner.int8.onnx", "joiner.onnx"));
      config.setTransducer(transducer);
      config.setTokens(find(directory, "tokens.txt"));
      config.setModelType("nemo_transducer");
    } else if (family.equals("whisper")) {
      var whisper = new OfflineWhisperModelConfig();
      whisper.setEncoder(
          find(
              directory,
              "base-encoder.int8.onnx",
              "tiny-encoder.int8.onnx",
              "small-encoder.int8.onnx"));
      whisper.setDecoder(
          find(
              directory,
              "base-decoder.int8.onnx",
              "tiny-decoder.int8.onnx",
              "small-decoder.int8.onnx"));
      whisper.setLanguage("ru");
      whisper.setTask("transcribe");
      config.setWhisper(whisper);
      config.setTokens(find(directory, "base-tokens.txt", "tiny-tokens.txt", "small-tokens.txt"));
      config.setModelType("whisper");
    } else throw new IllegalArgumentException("Unknown model family: " + family);
    return config;
  }

  private static String hex(byte[] bytes) {
    var result = new StringBuilder();
    for (byte value : bytes) result.append(String.format("%02x", value & 255));
    return result.toString();
  }

  private static JsonObject modelHashes(Path directory, OfflineModelConfig config)
      throws Exception {
    var paths = new java.util.LinkedHashSet<Path>();
    paths.add(directory.resolve("silero_vad.onnx"));
    if (config == null) {
      for (String name :
          java.util.List.of("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        paths.add(directory.resolve(name));
    } else {
      paths.add(Path.of(config.getTokens()));
      if (config.getModelType().equals("whisper")) {
        paths.add(Path.of(config.getWhisper().getEncoder()));
        paths.add(Path.of(config.getWhisper().getDecoder()));
      } else {
        paths.add(Path.of(config.getTransducer().getEncoder()));
        paths.add(Path.of(config.getTransducer().getDecoder()));
        paths.add(Path.of(config.getTransducer().getJoiner()));
      }
    }
    var hashes = new JsonObject();
    for (Path path : paths) {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      hashes.addProperty(path.getFileName().toString(), hex(digest.digest()));
    }
    return hashes;
  }

  private static String find(Path directory, String... names) throws IOException {
    for (String name : names) {
      Path path = directory.resolve(name);
      if (Files.isRegularFile(path) && Files.size(path) > 0)
        return path.toAbsolutePath().toString();
    }
    throw new IOException("Missing model file in " + directory + ": " + String.join(" or ", names));
  }

  private record Audio(Path path) implements PcmAudio {
    public OptionalLong sampleCount() {
      try {
        return OptionalLong.of(Files.size(path) / 2);
      } catch (IOException exception) {
        throw new java.io.UncheckedIOException(exception);
      }
    }

    public Reader openReader() throws IOException {
      long count = sampleCount().orElseThrow();
      if (Files.size(path) % 2 != 0) throw new IOException("Odd PCM16 byte count");
      var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
      return new Reader() {
        long remaining = count;

        public int read(float[] destination, int offset, int length) throws IOException {
          if (length == 0) return 0;
          if (remaining == 0) return -1;
          int size = (int) Math.min(length, remaining);
          for (int i = 0; i < size; i++)
            destination[offset + i] = Short.reverseBytes(input.readShort()) / 32768f;
          remaining -= size;
          return size;
        }

        public void close() throws IOException {
          input.close();
        }
      };
    }

    public void close() {}
  }
}
