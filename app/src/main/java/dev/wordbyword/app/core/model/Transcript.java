package dev.wordbyword.app.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable snapshot; utterances are presented in chronological order by the assembler. */
public record Transcript(List<Speaker> speakers, List<Utterance> utterances) {
  public Transcript {
    speakers = List.copyOf(speakers);
    utterances = List.copyOf(utterances);
    Set<String> ids = new HashSet<>();
    for (Speaker speaker : speakers) {
      if (!ids.add(speaker.id())) {
        throw new IllegalArgumentException("Duplicate speaker ID: " + speaker.id());
      }
    }
    for (Utterance utterance : utterances) {
      if (utterance.speakerId().isPresent() && !ids.contains(utterance.speakerId().get())) {
        throw new IllegalArgumentException("Utterance refers to an unknown speaker");
      }
    }
  }
}
