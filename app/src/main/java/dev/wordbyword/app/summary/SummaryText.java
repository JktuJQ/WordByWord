package dev.wordbyword.app.summary;

import dev.wordbyword.app.core.transcript.Transcript;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public final class SummaryText {
  private SummaryText() {}

  public static List<String> passages(String chunk) {
    var result = new ArrayList<String>();
    for (String passage : chunk.split("(?<=[.!?])\\s+|\\n+")) {
      if (passage.isBlank()) continue;
      if (result.size() < 16) result.add(passage.trim());
      else result.set(15, result.get(15) + " " + passage.trim());
    }
    return List.copyOf(result);
  }

  public static String select(List<String> passages, String answer) {
    String numbers = answer.trim();
    if (numbers.startsWith("[") && numbers.endsWith("]"))
      numbers = numbers.substring(1, numbers.length() - 1).trim();
    if (!numbers.matches("[1-9][0-9]*(\\s*,\\s*[1-9][0-9]*){0,2}"))
      throw new IllegalArgumentException("Модель вернула некорректные номера фрагментов");
    var selected = new TreeSet<Integer>();
    for (String number : numbers.split(",")) {
      int index = Integer.parseInt(number.trim()) - 1;
      if (index >= passages.size()) throw new IllegalArgumentException("Неизвестный фрагмент");
      selected.add(index);
    }
    StringBuilder result = new StringBuilder();
    for (int index : selected) result.append("• ").append(passages.get(index)).append('\n');
    return result.toString().trim();
  }

  public static String source(Transcript transcript) {
    StringBuilder text = new StringBuilder();
    for (var utterance : transcript.utterances()) {
      String speaker =
          utterance
              .speakerId()
              .flatMap(
                  id ->
                      transcript.speakers().stream()
                          .filter(value -> value.id().equals(id))
                          .findFirst())
              .map(Transcript.Speaker::displayName)
              .orElse("Участник");
      text.append(speaker).append(": ").append(utterance.text()).append('\n');
    }
    return text.toString().trim();
  }

  public static String key(String source) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 255));
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static List<String> chunks(String source) {
    var result = new ArrayList<String>();
    int start = 0;
    while (start < source.length()) {
      int end = start;
      int bytes = 0;
      int boundary = start;
      int sentenceBoundary = start;
      while (end < source.length()) {
        int codePoint = source.codePointAt(end);
        int next = end + Character.charCount(codePoint);
        int size = source.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > 512) break;
        bytes += size;
        end = next;
        if (Character.isWhitespace(codePoint)) {
          boundary = end;
          if (codePoint == '\n' || end > start + 1 && ".!?".indexOf(source.charAt(end - 2)) >= 0)
            sentenceBoundary = end;
        }
      }
      if (end < source.length()) {
        if (sentenceBoundary > start) end = sentenceBoundary;
        else if (boundary > start) end = boundary;
      }
      String chunk = source.substring(start, end).trim();
      if (!chunk.isEmpty()) result.add(chunk);
      start = end;
    }
    return List.copyOf(result);
  }
}
