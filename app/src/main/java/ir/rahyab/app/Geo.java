package ir.rahyab.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** محاسبات و قالب‌بندی‌های جغرافیایی؛ بدون وابستگی به اندروید. */
final class Geo {
    private Geo() { }

    /** فاصله دو نقطه روی کره زمین (متر)، فرمول هاورسین. */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static String coords(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    static String length(double meters) {
        return meters < 1000
                ? String.format(Locale.US, "%.0f m", meters)
                : String.format(Locale.US, "%.2f km", meters / 1000);
    }

    static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String isoTime(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String gpx(List<Point> track, List<Point> waypoints) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"Rahyab\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (Point p : waypoints) {
            element(b, "wpt", p, p.name);
        }
        if (!track.isEmpty()) {
            b.append("<trk><name>Rahyab track</name><trkseg>\n");
            for (Point p : track) {
                element(b, "trkpt", p, null);
            }
            b.append("</trkseg></trk>\n");
        }
        return b.append("</gpx>\n").toString();
    }

    private static void element(StringBuilder b, String tag, Point p, String name) {
        b.append(String.format(Locale.US, "<%s lat=\"%.7f\" lon=\"%.7f\">", tag, p.lat, p.lon));
        if (!Double.isNaN(p.alt)) {
            b.append(String.format(Locale.US, "<ele>%.1f</ele>", p.alt));
        }
        b.append("<time>").append(isoTime(p.time)).append("</time>");
        if (name != null) {
            b.append("<name>").append(escape(name)).append("</name>");
        }
        b.append("</").append(tag).append(">\n");
    }

    static String csv(List<Point> points) {
        // نویسه BOM تا اکسل متن فارسی را درست نشان دهد
        StringBuilder b = new StringBuilder("﻿name,latitude,longitude,altitude_m,accuracy_m,time_utc\n");
        for (Point p : points) {
            String name = p.name == null ? "" : p.name;
            // جلوگیری از اجرای متن به‌عنوان فرمول در صفحه‌گسترده
            if (!name.isEmpty() && "=+-@".indexOf(name.charAt(0)) >= 0) {
                name = "'" + name;
            }
            b.append('"').append(name.replace("\"", "\"\"")).append("\",")
                    .append(String.format(Locale.US, "%.7f,%.7f,", p.lat, p.lon))
                    .append(Double.isNaN(p.alt) ? "" : String.format(Locale.US, "%.1f", p.alt)).append(',')
                    .append(String.format(Locale.US, "%.1f", p.accuracy)).append(',')
                    .append(isoTime(p.time)).append('\n');
        }
        return b.toString();
    }
}
