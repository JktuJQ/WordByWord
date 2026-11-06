package dev.wordbyword.app.ui.view;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

public final class WindowInsetsHandler {
  private WindowInsetsHandler() {}

  public static void apply(View root) {
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            var bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else {
            applyLegacyInsets(view, insets);
          }
          return insets;
        });
  }

  @SuppressWarnings("deprecation")
  private static void applyLegacyInsets(View view, WindowInsets insets) {
    view.setPadding(
        insets.getSystemWindowInsetLeft(),
        insets.getSystemWindowInsetTop(),
        insets.getSystemWindowInsetRight(),
        insets.getSystemWindowInsetBottom());
  }
}
