package dev.wordbyword.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class VoiceOrbView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private float level;
  private float phase;

  public VoiceOrbView(Context context, AttributeSet attributes) {
    super(context, attributes);
  }

  public void setLevel(float value) {
    level = level * 0.7f + Math.max(0f, Math.min(1f, value)) * 0.3f;
    phase += 0.12f;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;
    float radius = Math.min(cx, cy) * (0.72f + 0.08f * level);
    path.reset();
    for (int i = 0; i <= 96; i++) {
      double angle = i * Math.PI * 2 / 96;
      double ripple = Math.sin(angle * 5 + phase) * 0.11 + Math.sin(angle * 8 - phase * 0.7) * 0.04;
      float r = radius * (1f + (float) ripple * level);
      float x = cx + r * (float) Math.cos(angle);
      float y = cy + r * (float) Math.sin(angle);
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), Color.rgb(166, 151, 255),
        Color.rgb(89, 71, 245), Shader.TileMode.CLAMP));
    canvas.drawPath(path, paint);
  }
}
