package dev.wordbyword.app.summary;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public final class SummaryTextTest {
  @Test
  public void selectionCopiesOnlyExistingPassagesInSourceOrder() {
    var passages = List.of("Встреча завтра.", "Сервер не покупаем.", "Смета готова.");
    assertEquals(
        "• Встреча завтра.\n• Сервер не покупаем.", SummaryText.select(passages, "[2, 1, 2]"));
    assertThrows(IllegalArgumentException.class, () -> SummaryText.select(passages, "4"));
    assertThrows(
        IllegalArgumentException.class, () -> SummaryText.select(passages, "Сервер купили"));
    assertThrows(IllegalArgumentException.class, () -> SummaryText.select(passages, "0"));
  }

  @Test
  public void passagesPreserveAllContentWithBoundedNumbering() {
    String input = "Первое. Второе!\nТретье? ".repeat(20).trim();
    var passages = SummaryText.passages(input);
    assertEquals(16, passages.size());
    assertEquals(input.replaceAll("\\s+", " "), String.join(" ", passages));
  }

  @Test
  public void chunksPreserveAllWordsAndBoundUtf8IncludingEmoji() {
    String input = ("Иван: Встреча завтра в 15:00. Не покупаем сервер. 😀\n").repeat(80);
    var chunks = SummaryText.chunks(input);
    assertTrue(chunks.size() > 1);
    assertEquals(
        input.trim().replaceAll("\\s+", " "), String.join(" ", chunks).replaceAll("\\s+", " "));
    for (String chunk : chunks) {
      assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length <= 512);
      assertFalse(chunk.contains("\uFFFD"));
    }
  }

  @Test
  public void splitsLongUnbrokenTextWithoutLosingCharacters() {
    String input = "ж😀".repeat(1000);
    assertEquals(input, String.join("", SummaryText.chunks(input)));
  }

  @Test
  public void blankTextHasNoChunks() {
    assertTrue(SummaryText.chunks(" \n ").isEmpty());
  }
}
