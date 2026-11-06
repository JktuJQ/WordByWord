package dev.wordbyword.app.core.model;

import java.util.Objects;

/** An identity scoped to one recording; displayName may change without changing id. */
public record Speaker(String id, String displayName) {
  public Speaker {
    Objects.requireNonNull(id);
    Objects.requireNonNull(displayName);
  }
}
