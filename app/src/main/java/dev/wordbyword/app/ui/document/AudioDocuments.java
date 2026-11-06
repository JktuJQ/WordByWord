package dev.wordbyword.app.ui.document;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import dev.wordbyword.app.R;
import dev.wordbyword.app.WordByWordApplication;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.export.TranscriptJsonExporter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.OptionalLong;
import java.util.UUID;

public final class AudioDocuments {
  private final WordByWordApplication app;
  private final Handler main = new Handler(Looper.getMainLooper());

  public AudioDocuments(WordByWordApplication app) {
    this.app = app;
  }

  public void importAudio(Uri uri) {
    importAudio(uri, null, false);
  }

  public void importRecordedAudio(Uri uri, String title) {
    importAudio(uri, title, true);
  }

  private void importAudio(Uri uri, String preferredTitle, boolean recorded) {
    message(R.string.importing);
    app.worker()
        .execute(
            () -> {
              try {
                var resolver = app.getContentResolver();
                String title = app.getString(R.string.imported_badge);
                try {
                  try (var cursor =
                      resolver.query(
                          uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                      title = cursor.getString(0);
                    }
                  }
                } catch (RuntimeException ignored) {
                }
                try (var input = resolver.openInputStream(uri)) {
                  if (input == null || input.read() == -1) {
                    throw new IOException("Empty file");
                  }
                }
                if (preferredTitle != null && !preferredTitle.isBlank()) {
                  title = preferredTitle.trim();
                }
                Recording recording =
                    new Recording(
                        UUID.randomUUID().toString(),
                        URI.create(uri.toString()),
                        title,
                        OptionalLong.empty());
                Recording saved = app.repository().saveRecording(recording);
                if (recorded) removeRecordingDraft(uri);
                main.post(() -> app.controller().add(saved));
              } catch (Exception exception) {
                main.post(() -> message(R.string.import_failed));
              }
            });
  }

  public void exportJson(Uri uri, Transcript transcript) {
    message(R.string.exporting);
    app.worker()
        .execute(
            () -> {
              try (var stream = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) {
                  throw new IOException("Unable to open output");
                }
                try (var writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                  TranscriptJsonExporter.write(transcript, writer);
                }
                main.post(() -> message(R.string.export_success));
              } catch (Exception exception) {
                main.post(() -> message(R.string.export_failed));
              }
            });
  }

  private void removeRecordingDraft(Uri uri) {
    if (!"file".equals(uri.getScheme()) || uri.getPath() == null) return;
    try {
      var source = new File(uri.getPath()).getCanonicalFile();
      var drafts = new File(app.getFilesDir(), "recordings").getCanonicalFile();
      if (drafts.equals(source.getParentFile())) Files.deleteIfExists(source.toPath());
    } catch (IOException exception) {
      Log.w("AudioDocuments", "Unable to remove saved recording draft", exception);
    }
  }

  private void message(int id) {
    app.controller().notifyMessage(app.getString(id));
  }
}
