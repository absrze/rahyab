package ir.rahyab.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** ذخیره مسیرها و نقاط روی خود گوشی. */
final class Db extends SQLiteOpenHelper {
    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) {
            instance = new Db(c.getApplicationContext());
        }
        return instance;
    }

    private Db(Context c) {
        super(c, "rahyab.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER, finish INTEGER, distance REAL)");
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, track INTEGER, lat REAL, lon REAL, alt REAL, acc REAL, time INTEGER)");
        db.execSQL("CREATE INDEX points_track ON points(track)");
        db.execSQL("CREATE TABLE waypoints(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lon REAL, alt REAL, acc REAL, time INTEGER)");
        createHazards(db);
    }

    private static void createHazards(SQLiteDatabase db) {
        // osm صفر یعنی گزارش خود کاربر
        db.execSQL("CREATE TABLE hazards(id INTEGER PRIMARY KEY AUTOINCREMENT, osm INTEGER, type INTEGER, lat REAL, lon REAL, speed INTEGER)");
        db.execSQL("CREATE INDEX hazards_place ON hazards(lat, lon)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createHazards(db);
        }
    }

    void addHazard(long osm, int type, double lat, double lon, int speed) {
        ContentValues v = new ContentValues();
        v.put("osm", osm);
        v.put("type", type);
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("speed", speed);
        getWritableDatabase().insertOrThrow("hazards", null, v);
    }

    /** داده تازه OSM را جایگزین قبلیِ همان محدوده می‌کند؛ گزارش‌های کاربر می‌مانند. */
    void replaceOsmHazards(double lat, double lon, List<Hazard> list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Hazard old : hazardsNear(lat, lon, HazardStore.RADIUS)) {
                if (!old.mine && Geo.distance(lat, lon, old.lat, old.lon) <= HazardStore.RADIUS) {
                    deleteHazard(old.id);
                }
            }
            for (Hazard h : list) {
                db.delete("hazards", "osm=?", args(h.id));
                addHazard(h.id, h.type, h.lat, h.lon, h.limit);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    List<Hazard> hazardsNear(double lat, double lon, double meters) {
        double dLat = meters / 111000;
        double dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        List<Hazard> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,osm,type,lat,lon,speed FROM hazards WHERE lat BETWEEN " + (lat - dLat) + " AND "
                        + (lat + dLat) + " AND lon BETWEEN " + (lon - dLon) + " AND " + (lon + dLon), null)) {
            while (c.moveToNext()) {
                list.add(new Hazard(c.getLong(0), c.getInt(2), c.getDouble(3), c.getDouble(4), c.getInt(5),
                        c.getLong(1) == 0));
            }
        }
        return list;
    }

    void deleteHazard(long id) {
        getWritableDatabase().delete("hazards", "id=?", args(id));
    }

    static final class Track {
        long id;
        long start;
        long finish;   // صفر یعنی در حال ثبت
        double distance;
    }

    long newTrack(long start) {
        ContentValues v = new ContentValues();
        v.put("start", start);
        v.put("finish", 0);
        v.put("distance", 0);
        return getWritableDatabase().insertOrThrow("tracks", null, v);
    }

    void addPoint(long track, Point p, double distance) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = values(p);
            v.put("track", track);
            db.insertOrThrow("points", null, v);
            db.execSQL("UPDATE tracks SET distance=? WHERE id=?", new Object[]{distance, track});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void finishTrack(long track, long finish) {
        ContentValues v = new ContentValues();
        v.put("finish", finish);
        getWritableDatabase().update("tracks", v, "id=?", args(track));
    }

    Track track(long id) {
        List<Track> list = query("id=?", args(id));
        return list.isEmpty() ? null : list.get(0);
    }

    List<Track> allTracks() {
        return query(null, null);
    }

    private List<Track> query(String where, String[] args) {
        List<Track> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id", "start", "finish", "distance"}, where, args, null, null, "id DESC")) {
            while (c.moveToNext()) {
                Track t = new Track();
                t.id = c.getLong(0);
                t.start = c.getLong(1);
                t.finish = c.getLong(2);
                t.distance = c.getDouble(3);
                list.add(t);
            }
        }
        return list;
    }

    /** نقاط مسیر که شناسه آن‌ها بزرگ‌تر از afterId است. */
    List<Point> points(long track, long afterId) {
        List<Point> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,lat,lon,alt,acc,time FROM points WHERE track=? AND id>? ORDER BY id",
                new String[]{String.valueOf(track), String.valueOf(afterId)})) {
            while (c.moveToNext()) {
                list.add(read(c, 1, c.getLong(0)));
            }
        }
        return list;
    }

    void deleteTrack(long id) {
        getWritableDatabase().delete("points", "track=?", args(id));
        getWritableDatabase().delete("tracks", "id=?", args(id));
    }

    void addWaypoint(String name, Point p) {
        ContentValues v = values(p);
        v.put("name", name);
        getWritableDatabase().insertOrThrow("waypoints", null, v);
    }

    List<Point> waypoints() {
        List<Point> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,lat,lon,alt,acc,time,name FROM waypoints ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                Point p = read(c, 1, c.getLong(0));
                p.name = c.getString(6);
                list.add(p);
            }
        }
        return list;
    }

    void deleteWaypoint(long id) {
        getWritableDatabase().delete("waypoints", "id=?", args(id));
    }

    private static String[] args(long id) {
        return new String[]{String.valueOf(id)};
    }

    private static ContentValues values(Point p) {
        ContentValues v = new ContentValues();
        v.put("lat", p.lat);
        v.put("lon", p.lon);
        if (Double.isNaN(p.alt)) {
            v.putNull("alt");
        } else {
            v.put("alt", p.alt);
        }
        v.put("acc", p.accuracy);
        v.put("time", p.time);
        return v;
    }

    private static Point read(Cursor c, int i, long id) {
        Point p = new Point(c.getDouble(i), c.getDouble(i + 1),
                c.isNull(i + 2) ? Double.NaN : c.getDouble(i + 2),
                c.getFloat(i + 3), c.getLong(i + 4));
        p.id = id;
        return p;
    }
}
