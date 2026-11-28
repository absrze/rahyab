package ir.rahyab.app;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** تشخیص خطرِ پیشِ رو؛ بدون وابستگی به اندروید. */
final class AlertEngine {
    static final double CAMERA_RANGE = 450;    // متر
    static final double BUMP_RANGE = 220;
    private static final double CONE = 35;     // درجه در هر طرف مسیر حرکت
    private static final double MIN_SPEED = 2.5;

    static final class Alert {
        final Hazard hazard;
        final double distance;
        final boolean first;    // اولین بار است؛ باید صدا پخش شود

        Alert(Hazard hazard, double distance, boolean first) {
            this.hazard = hazard;
            this.distance = distance;
            this.first = first;
        }
    }

    private final Set<Long> announced = new HashSet<>();

    /** نزدیک‌ترین خطر جلوی خودرو، یا null. */
    Alert update(List<Hazard> hazards, double lat, double lon, double bearing, double speed) {
        Hazard best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Hazard h : hazards) {
            double d = Geo.distance(lat, lon, h.lat, h.lon);
            if (d > 700) {
                announced.remove(h.id);     // دفعه بعد دوباره هشدار بده
                continue;
            }
            if (speed < MIN_SPEED || d > (h.type == Hazard.CAMERA ? CAMERA_RANGE : BUMP_RANGE)
                    || d >= bestDistance) {
                continue;
            }
            double off = Math.abs((Geo.bearing(lat, lon, h.lat, h.lon) - bearing + 540) % 360 - 180);
            // خیلی نزدیک، زاویه با خطای GPS بی‌معنی می‌شود
            if (off <= (d < 30 ? 80 : CONE)) {
                best = h;
                bestDistance = d;
            }
        }
        return best == null ? null : new Alert(best, bestDistance, announced.add(best.id));
    }
}
