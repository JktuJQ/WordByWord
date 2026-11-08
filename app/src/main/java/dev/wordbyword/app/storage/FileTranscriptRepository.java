package dev.wordbyword.app.storage;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.json.RecordingJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class FileTranscriptRepository implements TranscriptRepository {
  @FunctionalInterface
  public interface AudioSource {
    InputStream open(URI uri) throws IOException;
  }

  private static final Logger LOG = Logger.getLogger(FileTranscriptRepository.class.getName());
  private final Path root;
  private final AudioSource source;
  private final Consumer<IOException> readFailure;

  public FileTranscriptRepository(Path root, AudioSource source) {
    this(root, source, exception -> {});
  }

  public FileTranscriptRepository(
      Path root, AudioSource source, Consumer<IOException> readFailure) {
    this.root = root.toAbsolutePath().normalize();
    this.source = source;
    this.readFailure = readFailure;
  }

  @Override
  public synchronized List<Recording> listRecordings() throws IOException {
    Files.createDirectories(root);
    var recordings = new ArrayList<Recording>();
    try (var paths = Files.list(root)) {
      for (Path directory : paths.sorted().collect(Collectors.toList())) {
        if (directory.getFileName().toString().startsWith(".pending-")
            || directory.getFileName().toString().startsWith(".trash-")) {
          clean(directory);
          continue;
        }
        try {
          var entry = read(directory);
          recordings.add(entry.recording());
          cleanTemporaryFiles(directory);
        } catch (IOException exception) {
          LOG.log(Level.WARNING, "Cannot read recording " + directory.getFileName(), exception);
          readFailure.accept(exception);
        }
      }
    }
    return List.copyOf(recordings);
  }

  @Override
  public synchronized Optional<Transcript> find(String recordingId) throws IOException {
    Path directory = directory(recordingId);
    if (!Files.exists(directory)) return Optional.empty();
    var entry = read(directory);
    requireAudio(entry.recording());
    return Optional.ofNullable(entry.transcript());
  }

  @Override
  public synchronized Recording saveRecording(Recording recording) throws IOException {
    Files.createDirectories(root);
    Path directory = directory(recording.id());
    if (Files.exists(directory)) {
      var saved = read(directory).recording();
      requireAudio(saved);
      return saved;
    }
    Path staging = Files.createTempDirectory(root, ".pending-");
    try {
      try (var input = source.open(recording.audioUri())) {
        if (input == null) throw new IOException("Audio source is unavailable");
        Path audio = staging.resolve("audio");
        Files.copy(input, audio);
        try (var channel = FileChannel.open(audio, StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        if (Files.size(audio) == 0) throw new IOException("Audio source is empty");
      }
      URI audioUri = directory.resolve("audio").toUri();
      var saved =
          new Recording(recording.id(), audioUri, recording.title(), recording.durationMs());
      write(staging.resolve("recording.json"), saved, null);
      Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
      return saved;
    } finally {
      clean(staging);
    }
  }

  @Override
  public synchronized Transcript save(Transcript transcript) throws IOException {
    Recording original = saveRecording(transcript.recording());
    var savedRecording =
        new Recording(
            original.id(),
            original.audioUri(),
            transcript.recording().title(),
            transcript.recording().durationMs());
    var saved = new Transcript(savedRecording, transcript.speakers(), transcript.utterances());
    write(directory(savedRecording.id()).resolve("recording.json"), savedRecording, saved);
    return saved;
  }

  @Override
  public synchronized Transcript update(
      String recordingId, UnaryOperator<Transcript> transformation) throws IOException {
    Transcript transcript = find(recordingId).orElseThrow(NoSuchElementException::new);
    Transcript updated = Objects.requireNonNull(transformation.apply(transcript));
    if (!recordingId.equals(updated.recording().id()))
      throw new IllegalArgumentException("Cannot change recording ID");
    return save(updated);
  }

  @Override
  public synchronized Recording renameRecording(String recordingId, String title)
      throws IOException {
    if (title == null || title.isBlank()) throw new IllegalArgumentException("Empty title");
    Path directory = directory(recordingId);
    var entry = read(directory);
    var old = entry.recording();
    var renamed = new Recording(old.id(), old.audioUri(), title.trim(), old.durationMs());
    var transcript = entry.transcript();
    write(
        directory.resolve("recording.json"),
        renamed,
        transcript == null
            ? null
            : new Transcript(renamed, transcript.speakers(), transcript.utterances()));
    return renamed;
  }

  @Override
  public synchronized void delete(String recordingId) throws IOException {
    Path directory = directory(recordingId);
    if (!Files.exists(directory)) return;
    Path trash = root.resolve(".trash-" + UUID.randomUUID());
    Files.move(directory, trash, StandardCopyOption.ATOMIC_MOVE);
    clean(trash);
  }

  private RecordingJson.Entry read(Path directory) throws IOException {
    RecordingJson.Entry entry;
    try (var reader =
        Files.newBufferedReader(directory.resolve("recording.json"), StandardCharsets.UTF_8)) {
      entry = RecordingJson.read(reader);
    }
    if (!directory.equals(directory(entry.recording().id())))
      throw new IOException("Recording ID mismatch");
    var original = entry.recording();
    var recording =
        new Recording(
            original.id(),
            directory.resolve("audio").toUri(),
            original.title(),
            original.durationMs());
    var transcript = entry.transcript();
    return new RecordingJson.Entry(
        recording,
        transcript == null
            ? null
            : new Transcript(recording, transcript.speakers(), transcript.utterances()));
  }

  private static void write(Path target, Recording recording, Transcript transcript)
      throws IOException {
    Path temporary = Files.createTempFile(target.getParent(), ".metadata-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        RecordingJson.write(writer, recording, transcript);
      }
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path directory(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty recording ID");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
      var name = new StringBuilder();
      for (byte value : digest) name.append(String.format(Locale.ROOT, "%02x", value & 255));
      return root.resolve(name.toString());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void requireAudio(Recording recording) throws IOException {
    if (!Files.isRegularFile(Paths.get(recording.audioUri()))
        || Files.size(Paths.get(recording.audioUri())) == 0) {
      throw new IOException("Saved audio is missing or empty");
    }
  }

  private static void cleanTemporaryFiles(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      for (Path file :
          files
              .filter(path -> path.getFileName().toString().startsWith(".metadata-"))
              .collect(Collectors.toList())) clean(file);
    }
  }

  private static void clean(Path path) {
    if (!Files.exists(path)) return;
    try (var files = Files.walk(path)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
        Files.deleteIfExists(file);
    } catch (IOException exception) {
      LOG.log(Level.WARNING, "Cannot remove temporary recording files", exception);
    }
  }
}
