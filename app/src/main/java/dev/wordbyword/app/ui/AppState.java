package dev.wordbyword.app.ui;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.util.List;
import java.util.OptionalDouble;

public record AppState(
    List<Item> items, String selectedId, boolean busy, boolean cancelling, String message) {
  public AppState {
    items = List.copyOf(items);
  }

  public Item selected() {
    return find(selectedId);
  }

  public Item find(String id) {
    return items.stream().filter(item -> item.recording().id().equals(id)).findFirst().orElse(null);
  }

  public enum Status {
    IMPORTED,
    PROCESSING,
    READY,
    ERROR,
    CANCELLED
  }

  public record Item(
      Recording recording,
      Status status,
      String detail,
      OptionalDouble progress,
      Transcript transcript) {}
}
