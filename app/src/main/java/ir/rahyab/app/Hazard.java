package ir.rahyab.app;

/** دوربین سرعت یا سرعت‌گیر. */
final class Hazard {
    static final int CAMERA = 1;
    static final int BUMP = 2;

    final long id;
    final int type;
    final double lat;
    final double lon;
    final int limit;        // سرعت مجاز کیلومتر بر ساعت؛ صفر یعنی نامشخص
    final boolean mine;     // گزارش خود کاربر

    Hazard(long id, int type, double lat, double lon, int limit, boolean mine) {
        this.id = id;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        this.limit = limit;
        this.mine = mine;
    }

    String title() {
        return type == CAMERA ? "دوربین سرعت" : "سرعت‌گیر";
    }
}
