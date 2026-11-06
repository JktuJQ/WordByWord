package dev.wordbyword.app.ui.view;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.ui.AppController;
import dev.wordbyword.app.ui.audio.TranscriptPlayback;
import java.util.HashMap;
import java.util.Locale;

public final class TranscriptView {
  private final View root;
  private final LinearLayout items;
  private final AppController controller;
  private final TranscriptPlayback playback;

  public TranscriptView(View root, LinearLayout items, AppController controller) {
    this.root = root;
    this.items = items;
    this.controller = controller;
    playback =
        new TranscriptPlayback(
            items.getContext(),
            () ->
                controller.notifyMessage(items.getContext().getString(R.string.audio_unavailable)));
  }

  public void release() {
    playback.release();
  }

  public void render(Transcript transcript) {
    playback.showRecording(transcript.recording());
    text(R.id.screen_title, transcript.recording().title());
    text(
        R.id.screen_description,
        items
            .getContext()
            .getString(
                R.string.recording_summary,
                transcript.utterances().size(),
                transcript.speakers().size()));
    if (transcript.utterances().isEmpty()) {
      var empty = new TextView(items.getContext());
      empty.setText(R.string.transcript_empty);
      items.addView(empty);
    }
    var speakers = new HashMap<String, Transcript.Speaker>();
    var colors = new HashMap<String, Integer>();
    for (int index = 0; index < transcript.speakers().size(); index++) {
      var speaker = transcript.speakers().get(index);
      speakers.put(speaker.id(), speaker);
      colors.put(speaker.id(), index);
    }
    LinearLayout speakerRow = new LinearLayout(items.getContext());
    speakerRow.setOrientation(LinearLayout.HORIZONTAL);
    speakerRow.setPadding(0, 18, 0, 6);
    for (var speaker : transcript.speakers()) {
      TextView chip = new TextView(items.getContext());
      chip.setText(speaker.displayName() + " ✎");
      chip.setTextColor(items.getContext().getColor(R.color.accent));
      chip.setBackgroundResource(R.drawable.notice);
      chip.setPadding(20, 12, 20, 12);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      params.setMargins(0, 0, 12, 0);
      speakerRow.addView(chip, params);
      chip.setOnClickListener(view -> rename(speaker));
    }
    items.addView(speakerRow);
    for (var utterance : transcript.utterances()) {
      View card =
          LayoutInflater.from(items.getContext()).inflate(R.layout.item_utterance, items, false);
      var participant = utterance.speakerId().map(speakers::get).orElse(null);
      TextView speaker = card.findViewById(R.id.speaker);
      speaker.setText(
          participant == null
              ? items.getContext().getString(R.string.unknown_speaker)
              : participant.displayName());
      if (participant != null) speaker.setOnClickListener(view -> rename(participant));
      int index = utterance.speakerId().map(id -> colors.getOrDefault(id, 0)).orElse(0);
      speaker.setTextColor(
          items.getContext().getColor(index % 2 == 0 ? R.color.accent : R.color.secondary_speaker));
      ((TextView) card.findViewById(R.id.timing))
          .setText(
              items
                  .getContext()
                  .getString(
                      R.string.time_range, time(utterance.startMs()), time(utterance.endMs())));
      ((TextView) card.findViewById(R.id.utterance_text)).setText(utterance.text());
      card.findViewById(R.id.play_utterance)
          .setOnClickListener(view -> playback.playAt(transcript.recording(), utterance.startMs()));
      card.findViewById(R.id.seek_back)
          .setOnClickListener(
              view -> playback.seek(transcript.recording(), utterance.startMs(), -5000));
      card.findViewById(R.id.seek_forward)
          .setOnClickListener(
              view -> playback.seek(transcript.recording(), utterance.startMs(), 5000));
      items.addView(card);
    }
  }

  private void rename(Transcript.Speaker speaker) {
    Context context = items.getContext();
    EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setText(speaker.displayName());
    input.setSelectAllOnFocus(true);
    LinearLayout holder = new LinearLayout(context);
    holder.setPadding(40, 0, 40, 0);
    holder.addView(input);
    new AlertDialog.Builder(context)
        .setTitle(R.string.rename_participant)
        .setView(holder)
        .setNegativeButton(R.string.not_now, null)
        .setPositiveButton(
            R.string.save_name,
            (dialog, which) -> {
              String name = input.getText().toString().trim();
              if (!name.isEmpty()) controller.renameSpeaker(speaker.id(), name);
            })
        .show();
  }

  private static String time(long milliseconds) {
    long seconds = milliseconds / 1000;
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private void text(int id, String value) {
    ((TextView) root.findViewById(id)).setText(value);
  }
}
