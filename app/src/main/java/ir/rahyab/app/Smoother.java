package ir.rahyab.app;

/** فیلتر کالمن ساده: لرزش GPS را کم می‌کند بدون آن‌که خط زیاد از راهرو عقب بماند. */
final class Smoother {
    private Point state;
    private double variance;

    Point apply(Point p) {
        double noise = (double) p.accuracy * p.accuracy;
        if (state == null || p.time - state.time > 10_000 || p.time < state.time
                || Geo.distance(state.lat, state.lon, p.lat, p.lon) > 3 * p.accuracy + 30) {
            state = p;
            variance = noise;
            return p;
        }
        double seconds = (p.time - state.time) / 1000.0;
        // جابه‌جایی بیشتر از خطای GPS یعنی حرکت سریع؛ آن‌وقت کمتر صاف می‌شود
        double beyond = Math.max(0, Geo.distance(state.lat, state.lon, p.lat, p.lon) - p.accuracy);
        double drift = 2.5 + 1.5 * beyond / Math.max(1, seconds);
        variance += seconds * drift * drift;
        double k = variance / (variance + Math.max(1, noise));
        state = new Point(state.lat + k * (p.lat - state.lat), state.lon + k * (p.lon - state.lon),
                p.alt, p.accuracy, p.time);
        variance *= 1 - k;
        return state;
    }
}
