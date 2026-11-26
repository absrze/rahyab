package ir.rahyab.app;

/** میانگین وزنی چند موقعیت GPS هنگام ایستادن، برای ثبت دقیق‌تر یک نقطه. */
final class Averager {
    private Point first;
    private Point last;
    private double weight;
    private double lat;
    private double lon;
    private double altWeight;
    private double alt;
    private float best = Float.MAX_VALUE;
    int count;

    /** false یعنی نمونه تکراری، بسیار ضعیف یا دور از نقطه اول است. */
    boolean add(Point p) {
        if (p.accuracy > TrackFilter.MAX_ACCURACY || (last != null && p.time <= last.time)) {
            return false;
        }
        if (first != null && Geo.distance(first.lat, first.lon, p.lat, p.lon)
                > Math.max(15, first.accuracy + p.accuracy)) {
            return false;
        }
        if (first == null) {
            first = p;
        }
        last = p;
        double w = 1.0 / Math.max(1, (double) p.accuracy * p.accuracy);
        weight += w;
        lat += w * p.lat;
        lon += w * p.lon;
        if (!Double.isNaN(p.alt)) {
            altWeight += w;
            alt += w * p.alt;
        }
        best = Math.min(best, p.accuracy);
        count++;
        return true;
    }

    /** null یعنی هنوز نمونه‌ای نیست. دقت اعلام‌شده بهترین دقت دیده‌شده است. */
    Point result() {
        if (count == 0) {
            return null;
        }
        return new Point(lat / weight, lon / weight, altWeight > 0 ? alt / altWeight : Double.NaN,
                best, last.time);
    }
}
