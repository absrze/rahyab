package ir.rahyab.app;

/** تصمیم می‌گیرد کدام موقعیت GPS وارد خط مسیر شود تا خط «لرزان» و پرشی نشود. */
final class TrackFilter {
    static final float MAX_ACCURACY = 35f;   // زیر درختان دقت کمتر می‌شود
    static final double MIN_STEP = 4;        // متر
    static final double MAX_SPEED = 70;      // متر بر ثانیه
    private static final int MAX_JUMPS = 5;

    private Point last;
    private int jumps;

    TrackFilter(Point last) {
        this.last = last;
    }

    /** فاصله افزوده‌شده (متر) را برمی‌گرداند؛ ‎-1 یعنی نقطه رد شد. */
    double offer(Point p) {
        if (p.accuracy > MAX_ACCURACY) {
            return -1;
        }
        if (last == null) {
            last = p;
            return 0;
        }
        double d = Geo.distance(last.lat, last.lon, p.lat, p.lon);
        // هنگام ایستادن، نوسان GPS نباید مسافت بسازد
        if (d < Math.max(MIN_STEP, p.accuracy * 0.5)) {
            return -1;
        }
        double seconds = Math.max(1, (p.time - last.time) / 1000.0);
        if (d / seconds > MAX_SPEED) {
            if (++jumps < MAX_JUMPS) {
                return -1;
            }
            // پرش مداوم: نقطه قبلی غلط بوده؛ ادامه بده ولی مسافت پرش را نشمار
            d = 0;
        }
        jumps = 0;
        last = p;
        return d;
    }
}
