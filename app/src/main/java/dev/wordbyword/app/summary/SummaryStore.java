package dev.wordbyword.app.summary;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class SummaryStore {
  private record Entry(String key, String text) {}

  private final Path library;
  private final Gson json = new Gson();

  public SummaryStore(Path library) {
    this.library = library.toAbsolutePath().normalize();
  }

  public String load(String recordingId, String key) throws IOException {
    Path file = file(recordingId);
    if (!Files.isRegularFile(file)) return "";
    try {
      Entry entry =
          json.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Entry.class);
      return entry != null && key.equals(entry.key()) && entry.text() != null ? entry.text() : "";
    } catch (RuntimeException exception) {
      throw new IOException("Не удалось прочитать краткое содержание", exception);
    }
  }

  public void save(String recordingId, String key, String text) throws IOException {
    Path file = file(recordingId);
    Path temporary = file.resolveSibling("summary-" + UUID.randomUUID() + ".tmp");
    try {
      Files.write(temporary, json.toJson(new Entry(key, text)).getBytes(StandardCharsets.UTF_8));
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path file(String id) throws IOException {
    if (id == null
        || id.isBlank()
        || id.contains("/")
        || id.contains("\\")
        || id.equals(".")
        || id.equals("..")) throw new IOException("Некорректная запись");
    Path directory = library.resolve(SummaryText.key(id));
    if (!directory.startsWith(library) || !Files.isDirectory(directory))
      throw new IOException("Запись не найдена");
    return directory.resolve("summary.json");
  }
}
