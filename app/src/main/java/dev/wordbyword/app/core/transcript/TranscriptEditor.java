package dev.wordbyword.app.core.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class TranscriptEditor {
  private final Transcript original;
  private final List<Transcript.Speaker> speakers;

  public TranscriptEditor(Transcript transcript) {
    original = Objects.requireNonNull(transcript);
    speakers = new ArrayList<>(transcript.speakers());
  }

  public TranscriptEditor renameSpeaker(String speakerId, String displayName) {
    if (displayName == null || displayName.isBlank())
      throw new IllegalArgumentException("Empty name");
    for (int i = 0; i < speakers.size(); i++) {
      if (speakers.get(i).id().equals(speakerId)) {
        speakers.set(i, new Transcript.Speaker(speakerId, displayName.trim()));
        return this;
      }
    }
    throw new NoSuchElementException(speakerId);
  }

  public Transcript build() {
    return new Transcript(original.recording(), speakers, original.utterances());
  }
}
