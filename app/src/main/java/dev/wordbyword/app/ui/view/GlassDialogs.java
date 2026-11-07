package dev.wordbyword.app.ui.view;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import dev.wordbyword.app.R;
import java.util.function.Consumer;

public final class GlassDialogs {
  private GlassDialogs() {}

  private static int dp(Context context, int size) {
    return Math.round(size * context.getResources().getDisplayMetrics().density);
  }

  private static LinearLayout panel(Context context) {
    LinearLayout panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24));
    panel.setBackgroundResource(R.drawable.dialog_surface);
    panel.setClipToOutline(true);
    return panel;
  }

  private static TextView text(Context context, String value, int size) {
    TextView text = new TextView(context);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(context.getColor(R.color.ink));
    return text;
  }

  public static void confirm(Context context, String title, String body, Runnable confirm) {
    show(context, title, body, null, value -> confirm.run(), "Удалить");
  }

  public static void rename(Context context, String title, String initial, Consumer<String> save) {
    show(context, title, null, initial, save, "Сохранить");
  }

  private static void show(
      Context context,
      String title,
      String body,
      String initial,
      Consumer<String> action,
      String actionLabel) {
    Dialog dialog = new Dialog(context);
    LinearLayout panel = panel(context);
    TextView heading = text(context, title, 26);
    heading.setTypeface(context.getResources().getFont(R.font.soyuz_grotesk));
    panel.addView(heading);
    EditText input = new EditText(context);
    if (initial != null) {
      input.setText(initial);
      input.setSingleLine(true);
      input.setSelectAllOnFocus(true);
      input.setTextSize(16);
      input.setPadding(dp(context, 16), 0, dp(context, 16), 0);
      input.setBackgroundResource(R.drawable.recording_name_field);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(context, 56));
      params.topMargin = dp(context, 24);
      panel.addView(input, params);
    } else if (body != null) {
      TextView description = text(context, body, 16);
      description.setPadding(0, dp(context, 16), 0, 0);
      panel.addView(description);
    }
    LinearLayout buttons = new LinearLayout(context);
    buttons.setPadding(0, dp(context, 24), 0, 0);
    Button cancel = new Button(context);
    Button accept = new Button(context);
    for (Button button : new Button[] {cancel, accept}) {
      button.setAllCaps(false);
      button.setTextColor(context.getColor(R.color.ink));
      button.setBackgroundTintList(null);
      button.setBackgroundResource(R.drawable.home_liquid_glass);
      button.setStateListAnimator(null);
      button.setElevation(dp(context, 2));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 52), 1);
      if (button == accept) params.leftMargin = dp(context, 12);
      buttons.addView(button, params);
    }
    cancel.setText("Отмена");
    accept.setText(actionLabel);
    cancel.setOnClickListener(view -> dialog.dismiss());
    accept.setOnClickListener(
        view -> {
          String value = input.getText().toString().trim();
          if (initial != null && value.isEmpty()) {
            input.setError("Введите название");
            return;
          }
          dialog.dismiss();
          action.accept(value);
        });
    panel.addView(buttons);
    dialog.setContentView(panel);
    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    dialog.show();
    dialog
        .getWindow()
        .setLayout(
            context.getResources().getDisplayMetrics().widthPixels - dp(context, 40),
            ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  public static PopupWindow message(View anchor, String value) {
    Context context = anchor.getContext();
    LinearLayout panel = panel(context);
    panel.setBackgroundResource(R.drawable.recording_name_field);
    panel.addView(text(context, value, 16));
    PopupWindow popup =
        new PopupWindow(
            panel,
            context.getResources().getDisplayMetrics().widthPixels - dp(context, 40),
            -2,
            false);
    popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    popup.setElevation(dp(context, 12));
    anchor.post(
        () -> {
          if (!anchor.isAttachedToWindow()) return;
          popup.showAtLocation(
              anchor, Gravity.CENTER, 0, 0);
          anchor.postDelayed(popup::dismiss, 2800);
        });
    return popup;
  }
}
