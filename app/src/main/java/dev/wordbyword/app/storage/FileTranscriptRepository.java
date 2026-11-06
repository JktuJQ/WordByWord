package dev.wordbyword.app.storage;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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
      URI audioUri = recording.audioUri();
      if (!"demo".equals(audioUri.getScheme())) {
        try (var input = source.open(audioUri)) {
          if (input == null) throw new IOException("Audio source is unavailable");
          Path audio = staging.resolve("audio");
          Files.copy(input, audio);
          try (var channel =
              java.nio.channels.FileChannel.open(audio, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
          }
          if (Files.size(audio) == 0) throw new IOException("Audio source is empty");
        }
        audioUri = directory.resolve("audio").toUri();
      }
      var saved =
          new Recording(recording.id(), audioUri, recording.title(), recording.durationMs());
      RecordingJson.write(staging.resolve("recording.json"), saved, null);
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
    RecordingJson.write(
        directory(savedRecording.id()).resolve("recording.json"), savedRecording, saved);
    return saved;
  }

  @Override
  public synchronized void renameSpeaker(String recordingId, String speakerId, String displayName)
      throws IOException {
    if (displayName == null || displayName.isBlank())
      throw new IllegalArgumentException("Empty name");
    Transcript transcript = find(recordingId).orElseThrow(NoSuchElementException::new);
    if (transcript.speakers().stream().noneMatch(speaker -> speaker.id().equals(speakerId))) {
      throw new NoSuchElementException(speakerId);
    }
    var speakers =
        transcript.speakers().stream()
            .map(
                speaker ->
                    speaker.id().equals(speakerId)
                        ? new Transcript.Speaker(speakerId, displayName.trim())
                        : speaker)
            .collect(Collectors.toList());
    save(new Transcript(transcript.recording(), speakers, transcript.utterances()));
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
    var entry = RecordingJson.read(directory.resolve("recording.json"));
    if (!directory.equals(directory(entry.recording().id())))
      throw new IOException("Recording ID mismatch");
    var recording = entry.recording();
    if (!"demo".equals(recording.audioUri().getScheme())) {
      recording =
          new Recording(
              recording.id(),
              directory.resolve("audio").toUri(),
              recording.title(),
              recording.durationMs());
    }
    var transcript = entry.transcript();
    return new RecordingJson.Entry(
        recording,
        transcript == null
            ? null
            : new Transcript(recording, transcript.speakers(), transcript.utterances()));
  }

  private Path directory(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty recording ID");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var name = new StringBuilder();
      for (byte value : digest)
        name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      return root.resolve(name.toString());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void requireAudio(Recording recording) throws IOException {
    if (!"demo".equals(recording.audioUri().getScheme())
        && (!Files.isRegularFile(java.nio.file.Paths.get(recording.audioUri()))
            || Files.size(java.nio.file.Paths.get(recording.audioUri())) == 0)) {
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
