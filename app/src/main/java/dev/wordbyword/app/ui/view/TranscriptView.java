package dev.wordbyword.app.ui.view;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.audio.TranscriptPlayback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public final class TranscriptView {
  private final View root;
  private final LinearLayout items;
  private final AppController controller;
  private final TranscriptPlayback playback;
  private final ArrayList<CardRange> cards = new ArrayList<>();
  private final ScrollView scroll;
  private int activeCard = -1;
  private final PlaybackControls controls;
  private Transcript currentTranscript;

  private record CardRange(long startMs, long endMs, View card) {}

  public TranscriptView(View root, LinearLayout items, AppController controller) {
    this.root = root;
    this.items = items;
    this.controller = controller;
    this.scroll = root.findViewById(R.id.content_scroll);
    playback =
        new TranscriptPlayback(
            items.getContext(),
            this::updatePlaybackUi,
            () ->
                controller.notifyMessage(items.getContext().getString(R.string.audio_unavailable)));
    controls = new PlaybackControls(root, this::togglePlayback, this::seek, this::seekTo);
  }

  public void release() {
    playback.release();
    cards.clear();
    currentTranscript = null;
    activeCard = -1;
  }

  public void render(Transcript transcript) {
    currentTranscript = transcript;
    playback.showRecording(transcript.recording());
    text(R.id.screen_title, transcript.recording().title());
    text(
        R.id.screen_description,
        RecordingLabels.summary(transcript.utterances().size(), transcript.speakers().size()));
    cards.clear();
    activeCard = -1;
    var speakers = new HashMap<String, Transcript.Speaker>();
    var colors = new HashMap<String, Integer>();
    for (int index = 0; index < transcript.speakers().size(); index++) {
      var speaker = transcript.speakers().get(index);
      speakers.put(speaker.id(), speaker);
      colors.put(speaker.id(), index);
    }
    if (transcript.utterances().isEmpty()) {
      TextView empty = new TextView(items.getContext());
      empty.setText(R.string.transcript_empty);
      empty.setTextSize(16);
      empty.setTextColor(items.getContext().getColor(R.color.muted));
      empty.setPadding(0, dp(20), 0, dp(24));
      items.addView(empty);
    }
    for (var utterance : transcript.utterances()) {
      View card =
          LayoutInflater.from(items.getContext()).inflate(R.layout.item_utterance, items, false);
      var participant = utterance.speakerId().map(speakers::get).orElse(null);
      TextView speaker = card.findViewById(R.id.speaker);
      speaker.setText(
          participant == null
              ? items.getContext().getString(R.string.unknown_speaker)
              : participant.displayName());
      View edit = card.findViewById(R.id.rename_speaker);
      edit.setVisibility(participant == null ? View.GONE : View.VISIBLE);
      if (participant != null) edit.setOnClickListener(view -> rename(participant));
      int index = utterance.speakerId().map(id -> colors.getOrDefault(id, 0)).orElse(0);
      int speakerColor =
          items
              .getContext()
              .getColor(index % 2 == 0 ? R.color.accent_text : R.color.secondary_speaker);
      speaker.setTextColor(speakerColor);
      speaker.setTag(speakerColor);
      ((TextView) card.findViewById(R.id.timing))
          .setText(
              items
                  .getContext()
                  .getString(
                      R.string.time_range, time(utterance.startMs()), time(utterance.endMs())));
      ((TextView) card.findViewById(R.id.utterance_text)).setText(utterance.text());
      card.setOnClickListener(view -> playFrom(transcript, utterance.startMs()));
      cards.add(new CardRange(utterance.startMs(), utterance.endMs(), card));
      items.addView(card);
    }
    playback.refresh();
  }

  private void rename(Transcript.Speaker speaker) {
    GlassDialogs.rename(
        items.getContext(),
        "Имя участника",
        speaker.displayName(),
        name -> controller.renameSpeaker(speaker.id(), name));
  }

  private void togglePlayback() {
    if (currentTranscript != null) playback.toggle(currentTranscript.recording());
  }

  private void playFrom(Transcript transcript, long startMs) {
    playback.playAt(transcript.recording(), startMs);
  }

  private void seek(long offsetMs) {
    if (currentTranscript != null) playback.seek(currentTranscript.recording(), offsetMs);
  }

  private void seekTo(long positionMs) {
    if (currentTranscript != null) playback.seekTo(currentTranscript.recording(), positionMs);
  }

  private void updatePlaybackUi(TranscriptPlayback.State state) {
    int position = state.positionMs();
    controls.render(state);
    boolean playing = state.playing();
    int newActive = -1;
    for (int index = 0; index < cards.size(); index++) {
      CardRange range = cards.get(index);
      boolean active = playing && position >= range.startMs() && position < range.endMs();
      range.card().setBackgroundResource(active ? R.drawable.active_utterance : R.drawable.card);
      TextView speaker = range.card().findViewById(R.id.speaker);
      speaker.setTextColor(
          active ? items.getContext().getColor(R.color.active_accent) : (Integer) speaker.getTag());
      ((TextView) range.card().findViewById(R.id.utterance_text))
          .setTextColor(items.getContext().getColor(R.color.ink));
      ((TextView) range.card().findViewById(R.id.timing))
          .setTextColor(items.getContext().getColor(active ? R.color.active_muted : R.color.muted));
      if (active) newActive = index;
    }
    if (newActive != activeCard) {
      activeCard = newActive;
      if (activeCard >= 0) {
        View card = cards.get(activeCard).card();
        scroll.smoothScrollTo(
            0, Math.max(0, items.getTop() + card.getTop() - scroll.getHeight() / 3));
      }
    }
  }

  private int dp(int value) {
    return Math.round(value * items.getResources().getDisplayMetrics().density);
  }

  private static String time(long milliseconds) {
    long seconds = milliseconds / 1000;
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private void text(int id, String value) {
    ((TextView) root.findViewById(id)).setText(value);
  }
}
