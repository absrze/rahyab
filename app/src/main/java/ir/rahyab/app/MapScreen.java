package ir.rahyab.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Circle;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** همه کارهای رسم نقشه و خط مسیر. */
final class MapScreen {
    private final Context context;
    private final MapView view;
    private TileCache cache;
    private TileRendererLayer base;
    private final List<LatLong> line = new ArrayList<>();
    private final List<Layer> trackLayers = new ArrayList<>();
    private final List<Layer> wayLayers = new ArrayList<>();
    private Polyline border;
    private Polyline core;
    private Marker start;
    private Marker end;
    private Marker me;
    private Circle halo;
    private LatLong shown;          // جای فعلی آدمک روی صفحه
    private LatLong live;           // سر زنده خط (زیر پای آدمک)
    private boolean liveTail;
    private ValueAnimator walk;
    private final List<SatelliteLayer> satellite = new ArrayList<>();

    MapScreen(Context context, MapView view) {
        this.context = context;
        this.view = view;
        view.getMapScaleBar().setVisible(true);
        view.setBuiltInZoomControls(false);
        view.setCenter(new LatLong(35.6892, 51.3890));
        view.setZoomLevel((byte) 6);
    }

    private void removeBase() {
        if (base != null) {
            view.getLayerManager().getLayers().remove(base);
            base.onDestroy();
            cache.purge();
            base = null;
        }
        for (SatelliteLayer layer : satellite) {
            view.getLayerManager().getLayers().remove(layer);
            layer.close();
        }
        satellite.clear();
    }

    /** تصویر ماهواره‌ای (و در صورت وجود، لایه نام‌ها) را زیر همه لایه‌ها قرار می‌دهد. */
    void loadSatellite(TileProvider... providers) {
        removeBase();
        for (TileProvider provider : providers) {
            SatelliteLayer layer = new SatelliteLayer(provider);
            view.getLayerManager().getLayers().add(satellite.size(), layer);
            layer.setRedraw(() -> view.getLayerManager().redrawLayers());
            satellite.add(layer);
        }
        view.getLayerManager().redrawLayers();
    }

    /** وقتی کاشی‌ای ناموفق مانده، دوباره رسم می‌کند تا تلاش تازه انجام شود. */
    void retryTiles() {
        for (SatelliteLayer layer : satellite) {
            if (layer.hasFailures()) {
                view.getLayerManager().redrawLayers();
                return;
            }
        }
    }

    /** نقشه آفلاین را زیر همه لایه‌ها قرار می‌دهد. */
    void loadMap(File file) {
        removeBase();
        MapFile map = new MapFile(file);
        if (cache == null) {
            cache = AndroidUtil.createTileCache(context, "mapcache",
                    view.getModel().displayModel.getTileSize(), 1f,
                    view.getModel().frameBufferModel.getOverdrawFactor());
        }
        base = new TileRendererLayer(cache, map, view.getModel().mapViewPosition,
                AndroidGraphicFactory.INSTANCE);
        base.setXmlRenderTheme(MapsforgeThemes.MOTORIDER);
        view.getLayerManager().getLayers().add(0, base);
        view.getLayerManager().redrawLayers();
    }

    void center(double lat, double lon, int zoom) {
        view.setCenter(new LatLong(lat, lon));
        if (zoom > 0) {
            view.setZoomLevel((byte) zoom);
        }
    }

    void zoom(int delta) {
        int z = view.getModel().mapViewPosition.getZoomLevel() + delta;
        view.setZoomLevel((byte) Math.max(3, Math.min(20, z)));
    }

    void clearTrack() {
        for (Layer l : trackLayers) {
            view.getLayerManager().getLayers().remove(l);
        }
        trackLayers.clear();
        line.clear();
        liveTail = false;
        border = null;
        core = null;
        start = null;
        end = null;
        view.getLayerManager().redrawLayers();
    }

    /** نقاط تازه را به خط اضافه می‌کند. finished یعنی پرچم مقصد هم رسم شود. */
    void extendTrack(List<Point> points, boolean finished) {
        for (Point p : points) {
            line.add(new LatLong(p.lat, p.lon));
        }
        if (line.isEmpty()) {
            return;
        }
        if (core == null) {
            // دو خط روی هم: حاشیه سفید و مغز نارنجی برای دیده‌شدن روی جنگل
            border = new Polyline(paint(0xFFFFFFFF, 9), AndroidGraphicFactory.INSTANCE);
            core = new Polyline(paint(0xFFFF3D00, 5), AndroidGraphicFactory.INSTANCE);
            start = marker(line.get(0), R.drawable.dot_start);
            add(trackLayers, border);
            add(trackLayers, core);
            add(trackLayers, start);
        }
        drawLine();
        LatLong last = line.get(line.size() - 1);
        if (finished) {
            if (end == null) {
                end = marker(last, R.drawable.dot_end);
                add(trackLayers, end);
            }
            end.setLatLong(last);
        }
        view.getLayerManager().redrawLayers();
    }

    void showWaypoints(List<Point> points) {
        for (Layer l : wayLayers) {
            view.getLayerManager().getLayers().remove(l);
        }
        wayLayers.clear();
        for (Point p : points) {
            add(wayLayers, marker(new LatLong(p.lat, p.lon), R.drawable.dot_way));
        }
        view.getLayerManager().redrawLayers();
    }

    private void drawLine() {
        if (core == null) {
            return;
        }
        List<LatLong> points = new ArrayList<>(line);
        if (liveTail && live != null) {
            points.add(live);
        }
        border.setPoints(points);
        core.setPoints(points);
    }

    /** هنگام ثبت مسیر، خط تا زیر پای آدمک ادامه پیدا می‌کند. */
    void setLiveTail(boolean on) {
        if (liveTail != on) {
            liveTail = on;
            drawLine();
            view.getLayerManager().redrawLayers();
        }
    }

    /** آدمک را نرم به موقعیت تازه می‌برد. follow یعنی نقشه هم همراه او حرکت کند. */
    void moveMe(double lat, double lon, float accuracy, BooleanSupplier follow) {
        LatLong to = new LatLong(lat, lon);
        if (walk != null) {
            walk.cancel();
        }
        LatLong from = shown;
        // اولین موقعیت یا پرش بزرگ: بدون پویانمایی
        if (from == null || Geo.distance(from.latitude, from.longitude, lat, lon) > 200) {
            place(to, accuracy, follow.getAsBoolean());
            return;
        }
        walk = ValueAnimator.ofFloat(0f, 1f);
        walk.setDuration(900);
        walk.setInterpolator(new LinearInterpolator());
        walk.addUpdateListener(animator -> {
            float t = (float) animator.getAnimatedValue();
            place(new LatLong(from.latitude + (to.latitude - from.latitude) * t,
                    from.longitude + (to.longitude - from.longitude) * t), accuracy, follow.getAsBoolean());
        });
        walk.start();
    }

    /** آدمک در موقعیت فعلی، با دایره دقت GPS. */
    private void place(LatLong at, float accuracy, boolean follow) {
        shown = at;
        live = at;
        if (me == null) {
            Paint fill = AndroidGraphicFactory.INSTANCE.createPaint();
            fill.setColor(0x332196F3);
            fill.setStyle(Style.FILL);
            halo = new Circle(at, accuracy, fill, paint(0xAA2196F3, 1));
            Bitmap bitmap = AndroidGraphicFactory.convertToBitmap(context.getDrawable(R.drawable.person));
            // پای آدمک روی خود نقطه قرار می‌گیرد
            me = new Marker(at, bitmap, 0, -bitmap.getHeight() / 2);
            view.getLayerManager().getLayers().add(halo);
            view.getLayerManager().getLayers().add(me);
        } else {
            halo.setLatLong(at);
            halo.setRadius(accuracy);
            me.setLatLong(at);
        }
        if (liveTail) {
            drawLine();
        }
        if (follow) {
            view.setCenter(at);
        }
        view.getLayerManager().redrawLayers();
    }

    /** کل مسیر از مبدأ تا مقصد را در صفحه جا می‌دهد. */
    void fit(List<Point> points, int maxZoom) {
        if (points.isEmpty()) {
            return;
        }
        double south = 90;
        double west = 180;
        double north = -90;
        double east = -180;
        for (Point p : points) {
            south = Math.min(south, p.lat);
            north = Math.max(north, p.lat);
            west = Math.min(west, p.lon);
            east = Math.max(east, p.lon);
        }
        BoundingBox box = new BoundingBox(south, west, north, east);
        Dimension size = view.getModel().mapViewDimension.getDimension();
        int zoom = maxZoom;
        if (size != null && (north > south || east > west)) {
            // فقط بخش میانی صفحه آزاد است؛ پنل‌های بالا و پایین را کنار می‌گذاریم
            Dimension free = new Dimension(Math.max(1, size.width * 3 / 4), Math.max(1, size.height / 2));
            zoom = Math.min(maxZoom, LatLongUtils.zoomForBounds(free, box,
                    view.getModel().displayModel.getTileSize()));
        }
        view.setCenter(box.getCenterPoint());
        view.setZoomLevel((byte) Math.max(3, zoom));
    }

    private void add(List<Layer> group, Layer layer) {
        group.add(layer);
        view.getLayerManager().getLayers().add(layer);
        // نشانگر موقعیت من همیشه بالاترین لایه بماند
        if (me != null) {
            view.getLayerManager().getLayers().remove(halo);
            view.getLayerManager().getLayers().remove(me);
            view.getLayerManager().getLayers().add(halo);
            view.getLayerManager().getLayers().add(me);
        }
    }

    private Paint paint(int color, int widthDp) {
        Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(color);
        p.setStrokeWidth(widthDp * context.getResources().getDisplayMetrics().density);
        p.setStyle(Style.STROKE);
        return p;
    }

    private Marker marker(LatLong at, int drawableId) {
        Drawable d = context.getDrawable(drawableId);
        Bitmap bitmap = AndroidGraphicFactory.convertToBitmap(d);
        return new Marker(at, bitmap, 0, 0);
    }

    void destroy() {
        if (walk != null) {
            walk.cancel();
        }
        for (SatelliteLayer layer : satellite) {
            layer.close();
        }
        view.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
    }
}
