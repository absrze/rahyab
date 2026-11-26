package ir.rahyab.app;

final class Point {
    long id;
    String name;
    final double lat;
    final double lon;
    final double alt;       // NaN یعنی ارتفاع در دسترس نیست
    final float accuracy;   // متر
    final long time;        // میلی‌ثانیه

    Point(double lat, double lon, double alt, float accuracy, long time) {
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.accuracy = accuracy;
        this.time = time;
    }
}
