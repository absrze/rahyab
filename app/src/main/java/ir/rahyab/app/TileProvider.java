package ir.rahyab.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/** منبع کاشی‌های تصویر ماهواره‌ای. load روی رشته پس‌زمینه صدا زده می‌شود. */
abstract class TileProvider {
    final int minZoom;
    final int maxZoom;
    final int imageSize;

    TileProvider(int minZoom, int maxZoom, int imageSize) {
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.imageSize = imageSize;
    }

    abstract byte[] load(int z, int x, int y) throws IOException;

    void close() { }

    /** تصویر رایگان Sentinel-2 (دقت حدود ۱۰ متر). کاشی‌های دیده‌شده برای حالت آفلاین نگه داشته می‌شوند. */
    static TileProvider sentinel(Context c) {
        final File dir = new File(MapStore.dir(c), "satcache");
        return new TileProvider(0, 14, 256) {
            @Override
            byte[] load(int z, int x, int y) throws IOException {
                File f = new File(dir, z + "_" + x + "_" + y + ".jpg");
                if (f.isFile()) {
                    try (InputStream in = new FileInputStream(f)) {
                        return readAll(in);
                    }
                }
                byte[] data = http("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/"
                        + "GoogleMapsCompatible/" + z + "/" + y + "/" + x + ".jpg");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File part = new File(dir, f.getName() + ".part");
                    try (OutputStream out = new FileOutputStream(part)) {
                        out.write(data);
                    }
                    if (!part.renameTo(f)) {
                        part.delete();
                    }
                }
                return data;
            }
        };
    }

    /** حذف قدیمی‌ترین کاشی‌ها وقتی حافظه از حد بیشتر شود. */
    static void trimCache(Context c) {
        File[] files = new File(MapStore.dir(c), "satcache").listFiles();
        if (files == null) {
            return;
        }
        long[][] order = new long[files.length][2];
        long total = 0;
        for (int i = 0; i < files.length; i++) {
            order[i][0] = files[i].lastModified();
            order[i][1] = i;
            total += files[i].length();
        }
        Arrays.sort(order, (a, b) -> Long.compare(a[0], b[0]));
        for (int i = 0; i < order.length && total > 400L << 20; i++) {
            File f = files[(int) order[i][1]];
            total -= f.length();
            f.delete();
        }
    }

    /** فایل MBTiles تصویری که کاربر خودش آورده است؛ کاملاً آفلاین. */
    static TileProvider mbtiles(File file) {
        final SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        int min;
        int max;
        try (Cursor c = db.rawQuery("SELECT min(zoom_level), max(zoom_level) FROM tiles", null)) {
            if (!c.moveToFirst() || c.isNull(0)) {
                db.close();
                throw new IllegalStateException("empty mbtiles");
            }
            min = c.getInt(0);
            max = c.getInt(1);
        }
        // کاشی‌های برداری (pbf) پشتیبانی نمی‌شوند
        int size;
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)) {
            c.moveToFirst();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] data = c.getBlob(0);
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            size = o.outWidth;
        }
        if (size <= 0) {
            db.close();
            throw new IllegalStateException("not a raster mbtiles");
        }
        return new TileProvider(min, max, size) {
            @Override
            byte[] load(int z, int x, int y) throws IOException {
                synchronized (db) {
                    if (!db.isOpen()) {
                        throw new IOException("closed");
                    }
                    try (Cursor c = db.rawQuery(
                            "SELECT tile_data FROM tiles WHERE zoom_level=" + z + " AND tile_column=" + x
                                    + " AND tile_row=" + TileMath.tmsRow(z, y), null)) {
                        if (!c.moveToFirst()) {
                            throw new IOException("no tile");
                        }
                        return c.getBlob(0);
                    }
                }
            }

            @Override
            void close() {
                synchronized (db) {
                    db.close();
                }
            }
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] http(String address) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setRequestProperty("User-Agent", "Rahyab/3.0 (Android)");
        try {
            String type = h.getContentType();
            // پاسخ غیرتصویری (مثلاً صفحه خطا) پذیرفته نمی‌شود
            if (h.getResponseCode() != 200 || type == null || !type.startsWith("image/")) {
                throw new IOException("tile error " + h.getResponseCode());
            }
            try (InputStream in = h.getInputStream()) {
                return readAll(in);
            }
        } finally {
            h.disconnect();
        }
    }
}
