package dev.wordbyword.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public final class MascotView extends View {
  private final Bitmap sheet;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

  public MascotView(Context context, AttributeSet attributes) {
    super(context, attributes);
    sheet = BitmapFactory.decodeResource(getResources(), R.drawable.mascot_sheet);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawBitmap(
        sheet,
        new Rect(18, 15, 330, 276),
        new RectF(0, 0, getWidth(), getHeight()),
        paint);
  }
}
