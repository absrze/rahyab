package ir.rahyab.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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

        /** true هنگام شروع چرخش و false پس از پایان آن. */
        void rotating(boolean active);
    }

    private Listener listener;
    private ValueAnimator animator;
    private float angle;
    private float startAngle;
    private float lastAngle;
    private boolean rotating;
    private boolean busy;

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
            finishGesture();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            startAngle = fingers(e);
            lastAngle = startAngle;
        } else if (action == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
            float now = fingers(e);
            if (!rotating && Math.abs(normalize(now - startAngle)) > THRESHOLD) {
                rotating = true;
                lastAngle = now;
                stopAnimation();
                setBusy(true);
                // زوم و جابه‌جایی نقشه تا برداشتن انگشت‌ها متوقف شود
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            } else if (rotating) {
                apply(angle + normalize(now - lastAngle), true);
                lastAngle = now;
            }
        }
        if (rotating && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            finishGesture();
            return true;
        }
        return rotating || super.dispatchTouchEvent(e);
    }

    private void finishGesture() {
        if (rotating) {
            rotating = false;
            setBusy(false);
        }
    }

    /** هنگام چرخش، نقشه یک تصویر آماده روی پردازنده گرافیکی است و فقط چرخانده می‌شود. */
    private void setBusy(boolean active) {
        if (busy == active) {
            return;
        }
        busy = active;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setLayerType(active ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE, null);
        }
        if (listener != null) {
            listener.rotating(active);
        }
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
        if (rotating) {
            return;     // انگشت کاربر اولویت دارد
        }
        if (Math.abs(change) < 1) {
            apply(degrees, false);
            if (listener != null) {
                listener.rotating(false);
            }
            return;
        }
        setBusy(true);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(400);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator ended) {
                // هنگام لغو هم صدا زده می‌شود
                if (!rotating) {
                    setBusy(false);
                }
            }
        });
        animator.addUpdateListener(a -> apply(from + change * (float) a.getAnimatedValue(), false));
        animator.start();
    }
}
