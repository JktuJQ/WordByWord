package dev.wordbyword.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class VoiceOrbView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private Shader glow;
  private Shader sphere;
  private Shader ribbon;
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
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    float cx = width / 2f;
    float cy = height / 2f;
    float radius = Math.min(cx, cy) * 0.76f;
    glow = new RadialGradient(cx, cy, radius * 1.3f,
        new int[] {0x45FF8A27, 0x00FF8A27}, null, Shader.TileMode.CLAMP);
    sphere = new RadialGradient(cx - radius * 0.35f, cy - radius * 0.45f, radius * 1.75f,
        new int[] {Color.rgb(255, 238, 180), Color.rgb(255, 170, 36), Color.rgb(242, 87, 9)},
        new float[] {0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
    ribbon = new LinearGradient(cx, cy - radius * 0.3f, cx, cy + radius,
        new int[] {0xFFFFAD20, 0xFFF6670A, 0xFFFFB58F}, null, Shader.TileMode.CLAMP);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;
    float radius = Math.min(cx, cy) * (0.76f + 0.04f * level);
    paint.setShader(glow);
    canvas.drawCircle(cx, cy, radius * 1.3f, paint);
    paint.setShader(sphere);
    canvas.drawCircle(cx, cy, radius, paint);
    canvas.save();
    path.reset();
    path.addCircle(cx, cy, radius, Path.Direction.CW);
    canvas.clipPath(path);
    path.reset();
    float wave = (float) Math.sin(phase) * radius * 0.1f * level;
    path.moveTo(cx - radius, cy + radius * 0.3f);
    path.cubicTo(cx - radius * 0.1f, cy + radius + wave,
        cx + radius * 0.1f, cy - radius * 0.95f, cx + radius, cy - radius * 0.2f);
    path.lineTo(cx + radius, cy + radius);
    path.lineTo(cx - radius, cy + radius);
    path.close();
    paint.setShader(ribbon);
    canvas.drawPath(path, paint);
    canvas.restore();
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(getResources().getDisplayMetrics().density);
    paint.setColor(0xBFFFFFFF);
    canvas.drawCircle(cx, cy, radius, paint);
    paint.setStyle(Paint.Style.FILL);
  }
}
