package ir.rahyab.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.LruCache;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.layer.Layer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** لایه تصویر ماهواره‌ای: کاشی‌ها را در پس‌زمینه می‌گیرد و روی نقشه می‌کشد. */
final class SatelliteLayer extends Layer {
    private static final long RETRY_MS = 8000;

    private final TileProvider provider;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(
            (int) Math.min(128 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 4)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile boolean closed;
    private volatile Runnable redraw;

    SatelliteLayer(TileProvider provider) {
        this.provider = provider;
    }

    /** برای بارگذاری دوباره پس از رسیدن کاشی‌ها. */
    void setRedraw(Runnable redraw) {
        this.redraw = redraw;
    }

    @Override
    public void draw(BoundingBox boundingBox, byte zoomLevel, org.mapsforge.core.graphics.Canvas target,
                     Point topLeftPoint, Rotation rotation) {
        android.graphics.Canvas canvas = AndroidGraphicFactory.getCanvas(target);
        int tileSize = displayModel.getTileSize();
        int z = TileMath.sourceZoom(zoomLevel, tileSize, provider.imageSize, provider.minZoom, provider.maxZoom);
        double size = TileMath.screenSize(zoomLevel, tileSize, z);
        int count = 1 << z;
        int x0 = Math.max(0, (int) Math.floor(topLeftPoint.x / size));
        int y0 = Math.max(0, (int) Math.floor(topLeftPoint.y / size));
        int x1 = Math.min(count - 1, (int) Math.floor((topLeftPoint.x + canvas.getWidth()) / size));
        int y1 = Math.min(count - 1, (int) Math.floor((topLeftPoint.y + canvas.getHeight()) / size));
        // محافظت در برابر درخواست انبوه وقتی زوم از کمترین زوم فایل خیلی دورتر است
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 450) {
            return;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                RectF to = new RectF((float) (x * size - topLeftPoint.x), (float) (y * size - topLeftPoint.y),
                        (float) ((x + 1) * size - topLeftPoint.x), (float) ((y + 1) * size - topLeftPoint.y));
                Bitmap bitmap = memory.get(z + "/" + x + "/" + y);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, to, paint);
                } else {
                    drawParent(canvas, z, x, y, to);
                    request(z, x, y);
                }
            }
        }
    }

    /** تا رسیدن کاشی دقیق، تکه‌ای از کاشی بزرگ‌تر را نشان می‌دهد. */
    private void drawParent(android.graphics.Canvas canvas, int z, int x, int y, RectF to) {
        for (int up = 1; up <= 4 && z - up >= 0; up++) {
            Bitmap b = memory.get((z - up) + "/" + (x >> up) + "/" + (y >> up));
            if (b != null) {
                int part = Math.max(1, b.getWidth() >> up);
                int left = (x & ((1 << up) - 1)) * part;
                int top = (y & ((1 << up) - 1)) * part;
                canvas.drawBitmap(b, new Rect(left, top, left + part, top + part), to, paint);
                return;
            }
        }
    }

    private void request(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Long when = failed.get(key);
        if (closed || pending.size() > 200
                || (when != null && SystemClock.elapsedRealtime() - when < RETRY_MS) || !pending.add(key)) {
            return;
        }
        pool.execute(() -> {
            try {
                byte[] data = provider.load(z, x, y);
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b == null) {
                    failed.put(key, SystemClock.elapsedRealtime());
                } else {
                    memory.put(key, b);
                    failed.remove(key);
                    Runnable r = redraw;
                    if (r != null && !closed) {
                        r.run();
                    }
                }
            } catch (Exception e) {
                failed.put(key, SystemClock.elapsedRealtime());
            } finally {
                pending.remove(key);
            }
        });
    }

    /** پس از قطع و وصل اینترنت دوباره تلاش می‌شود. */
    boolean hasFailures() {
        return !failed.isEmpty();
    }

    void close() {
        closed = true;
        pool.shutdownNow();
        memory.evictAll();
        provider.close();
    }
}
