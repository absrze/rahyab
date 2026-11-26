package ir.rahyab.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** دانلود و نگهداری فایل نقشه آفلاین (قالب Mapsforge ‎.map). */
final class MapStore {
    static final String IRAN_URL = "https://download.mapsforge.org/maps/v5/asia/iran.map";

    private MapStore() { }

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    static File current(Context c) {
        String name = TrackingService.prefs(c).getString("map", null);
        if (name == null) {
            return null;
        }
        File f = new File(dir(c), name);
        return f.isFile() ? f : null;
    }

    static boolean downloading(Context c) {
        return TrackingService.prefs(c).getLong("download", -1) >= 0;
    }

    static void download(Context c, String url) {
        File part = new File(dir(c), "download.part");
        if (part.exists() && !part.delete()) {
            throw new IllegalStateException("cannot delete old download");
        }
        String name = Uri.parse(url).getLastPathSegment();
        if (name == null || !name.endsWith(".map")) {
            name = "offline.map";
        }
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setTitle("دانلود نقشه " + name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(c, null, part.getName());
        long id = manager(c).enqueue(r);
        TrackingService.prefs(c).edit().putLong("download", id).putString("downloadName", name).apply();
    }

    static void cancel(Context c) {
        long id = TrackingService.prefs(c).getLong("download", -1);
        if (id >= 0) {
            manager(c).remove(id);
            TrackingService.prefs(c).edit().remove("download").apply();
        }
    }

    /** متن وضعیت دانلود؛ null یعنی دانلودی در جریان نیست. done وقتی نقشه آماده شد اجرا می‌شود. */
    static String poll(Context c, Runnable done) {
        long id = TrackingService.prefs(c).getLong("download", -1);
        if (id < 0) {
            return null;
        }
        try (Cursor q = manager(c).query(new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) {
                cancel(c);
                return "دانلود لغو شد";
            }
            int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String name = TrackingService.prefs(c).getString("downloadName", "offline.map");
                boolean ok = install(c, new File(dir(c), "download.part"), name);
                TrackingService.prefs(c).edit().remove("download").apply();
                manager(c).remove(id);
                if (ok) {
                    done.run();
                    return "نقشه آماده شد";
                }
                return "ذخیره نقشه ناموفق بود";
            }
            if (status == DownloadManager.STATUS_FAILED) {
                cancel(c);
                return "دانلود ناموفق بود؛ اینترنت و فضای خالی را بررسی کنید";
            }
            long got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long all = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            String progress = all > 0 ? (100 * got / all) + "% از " + (all >> 20) + " MB" : (got >> 20) + " MB";
            return (status == DownloadManager.STATUS_PAUSED ? "دانلود متوقف (منتظر شبکه): " : "دانلود نقشه: ") + progress;
        }
    }

    /** کپی فایل انتخاب‌شده کاربر؛ در رشته پس‌زمینه صدا زده شود. */
    static void importFile(Context c, Uri uri) throws IOException {
        File part = new File(dir(c), "import.part");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(part)) {
            if (in == null) {
                throw new IOException("cannot open file");
            }
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        if (!install(c, part, "imported.map")) {
            throw new IOException("not a valid map file");
        }
    }

    /** فایل را پس از بررسی سرآیند آن جایگزین نقشه فعلی می‌کند. */
    private static boolean install(Context c, File part, String name) {
        try {
            new org.mapsforge.map.reader.MapFile(part).close();
        } catch (RuntimeException e) {
            part.delete();
            return false;
        }
        File target = new File(dir(c), name);
        File old = current(c);
        if (!part.renameTo(target)) {
            return false;
        }
        TrackingService.prefs(c).edit().putString("map", name).apply();
        if (old != null && !old.equals(target)) {
            old.delete();
        }
        return true;
    }

    private static DownloadManager manager(Context c) {
        return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
    }
}
