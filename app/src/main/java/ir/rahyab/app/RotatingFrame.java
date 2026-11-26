package ir.rahyab.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** نقشه را با دو انگشت می‌چرخاند. فرزند به اندازه قطر صفحه است تا گوشه‌ها خالی نماند. */
public class RotatingFrame extends FrameLayout {
    private static final float THRESHOLD = 10;  // درجه؛ کمتر از این زوم حساب می‌شود

    interface Listener {
        void rotated(float degrees, boolean byUser);
    }

    private Listener listener;
    private ValueAnimator animator;
    private float angle;
    private float startAngle;
    private float lastAngle;
    private boolean rotating;

    public RotatingFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    float angle() {
        return angle;
    }

    private int side(int w, int h) {
        return (int) Math.ceil(Math.hypot(w, h));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int spec = MeasureSpec.makeMeasureSpec(side(w, h), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(spec, spec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        int s = side(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout((w - s) / 2, (h - s) / 2, (w - s) / 2 + s, (h - s) / 2 + s);
            child.setPivotX(s / 2f);
            child.setPivotY(s / 2f);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            rotating = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            startAngle = fingers(e);
            lastAngle = startAngle;
        } else if (action == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
            float now = fingers(e);
            if (!rotating && Math.abs(normalize(now - startAngle)) > THRESHOLD) {
                rotating = true;
                lastAngle = now;
                // زوم و جابه‌جایی نقشه تا برداشتن انگشت‌ها متوقف شود
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            } else if (rotating) {
                stopAnimation();
                apply(angle + normalize(now - lastAngle), true);
                lastAngle = now;
            }
        }
        return rotating || super.dispatchTouchEvent(e);
    }

    private static float fingers(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    static float normalize(float degrees) {
        float d = degrees % 360;
        return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    private void apply(float degrees, boolean byUser) {
        angle = normalize(degrees);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setRotation(angle);
        }
        if (listener != null) {
            listener.rotated(angle, byUser);
        }
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** چرخش نرم از کوتاه‌ترین سمت. */
    void rotateTo(float degrees) {
        stopAnimation();
        float from = angle;
        float change = normalize(degrees - from);
        if (Math.abs(change) < 1) {
            apply(degrees, false);
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(400);
        animator.addUpdateListener(a -> apply(from + change * (float) a.getAnimatedValue(), false));
        animator.start();
    }
}
