package dev.wordbyword.app.core.model;

import java.net.URI;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Recording identity and metadata. The URI is opaque to consumers outside audio/storage adapters.
 */
public record Recording(String id, URI audioUri, String title, OptionalLong durationMs) {
  public Recording {
    Objects.requireNonNull(id);
    Objects.requireNonNull(audioUri);
    Objects.requireNonNull(title);
    Objects.requireNonNull(durationMs);
    if (durationMs.isPresent() && durationMs.getAsLong() < 0) {
      throw new IllegalArgumentException("Duration cannot be negative");
    }
  }
}
