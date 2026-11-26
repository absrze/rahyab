package ir.rahyab.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** قطب‌نمای روی نقشه؛ عقربه قرمز شمال نقشه را نشان می‌دهد. */
public class CompassView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float angle;

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setAngle(float degrees) {
        angle = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        canvas.rotate(angle, cx, cy);
        needle(canvas, cx, cy, cy - r * 0.72f, r * 0.22f, 0xFFFF5252);
        needle(canvas, cx, cy, cy + r * 0.72f, r * 0.22f, 0xFFCFD8DC);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r * 0.08f, paint);
    }

    private void needle(Canvas canvas, float cx, float cy, float tip, float half, int color) {
        path.reset();
        path.moveTo(cx, tip);
        path.lineTo(cx + half, cy);
        path.lineTo(cx - half, cy);
        path.close();
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }
}
