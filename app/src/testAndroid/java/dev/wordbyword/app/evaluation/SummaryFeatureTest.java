package dev.wordbyword.app.evaluation;

import static org.junit.Assert.*;

import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.wordbyword.app.summary.LocalSummarizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assume;
import org.junit.Test;

public final class SummaryFeatureTest {
  @Test
  public void bundledModelGeneratesAndReleasesOnCancellation() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Assume.assumeTrue(
        "Explicit opt-in required",
        "true".equals(InstrumentationRegistry.getArguments().getString("runSummary")));
    var context = instrumentation.getTargetContext();
    assertEquals(
        PackageManager.PERMISSION_DENIED,
        context.checkSelfPermission("android.permission.INTERNET"));
    var model = new LocalSummarizer(context);
    String source =
        "Анна: Встреча в пятницу в 15:00. Борис: Я подготовлю смету завтра. "
            + "Анна: Сервер пока не покупаем.";
    String result = model.summarize(source, new AtomicBoolean(), progress -> {});
    assertFalse(result.isBlank());
    for (String line : result.split("\\n")) assertTrue(source.contains(line.substring(2)));
    Files.write(
        context.getExternalFilesDir(null).toPath().resolve("summary-feature-output.txt"),
        result.getBytes(StandardCharsets.UTF_8));
    var cancelled = new AtomicBoolean(true);
    assertThrows(
        CancellationException.class, () -> model.summarize(source, cancelled, progress -> {}));
    var midGeneration = new AtomicBoolean();
    assertThrows(
        CancellationException.class,
        () ->
            model.summarize(
                source,
                midGeneration,
                progress -> {
                  if (progress.startsWith("Часть")) midGeneration.set(true);
                }));
    assertFalse(model.summarize(source, new AtomicBoolean(), progress -> {}).isBlank());
  }
}
