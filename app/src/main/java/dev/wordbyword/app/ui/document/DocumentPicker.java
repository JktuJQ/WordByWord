package dev.wordbyword.app.ui.document;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import dev.wordbyword.app.R;
import dev.wordbyword.app.ui.AppController;

public final class DocumentPicker {
  private static final int PICK_AUDIO = 100;
  private static final int CREATE_JSON = 101;
  private final Activity activity;
  private final AppController controller;
  private final AudioDocuments documents;
  private String pendingExportId;

  public DocumentPicker(
      Activity activity, AppController controller, AudioDocuments documents, Bundle savedState) {
    this.activity = activity;
    this.controller = controller;
    this.documents = documents;
    if (savedState != null) pendingExportId = savedState.getString("exportId");
  }

  public void saveState(Bundle state) {
    state.putString("exportId", pendingExportId);
  }

  public void pickAudio() {
    try {
      activity.startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .setType("audio/*")
              .addCategory(Intent.CATEGORY_OPENABLE),
          PICK_AUDIO);
    } catch (ActivityNotFoundException exception) {
      controller.notifyMessage(activity.getString(R.string.picker_unavailable));
    }
  }

  public void importRecordedAudio(Uri uri, String title) {
    documents.importAudio(uri, title);
  }

  public void pickExportFile() {
    var selected = controller.state().selected();
    if (selected == null || selected.transcript() == null) {
      return;
    }
    pendingExportId = selected.recording().id();
    try {
      activity.startActivityForResult(
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .setType("application/json")
              .addCategory(Intent.CATEGORY_OPENABLE)
              .putExtra(Intent.EXTRA_TITLE, "transcript-" + pendingExportId + ".json"),
          CREATE_JSON);
    } catch (ActivityNotFoundException exception) {
      pendingExportId = null;
      controller.notifyMessage(activity.getString(R.string.picker_unavailable));
    }
  }

  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
      if (requestCode == CREATE_JSON) {
        pendingExportId = null;
        controller.notifyMessage(activity.getString(R.string.export_cancelled));
      }
      return;
    }
    if (requestCode == PICK_AUDIO) {
      documents.importAudio(data.getData());
    } else if (requestCode == CREATE_JSON) {
      String id = pendingExportId;
      pendingExportId = null;
      var selected = controller.state().find(id);
      if (selected == null || selected.transcript() == null) {
        controller.notifyMessage(activity.getString(R.string.export_unavailable));
      } else {
        documents.exportJson(data.getData(), selected.transcript());
      }
    }
  }
}
