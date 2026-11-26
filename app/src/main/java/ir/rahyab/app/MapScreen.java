package ir.rahyab.app;

import android.content.Context;
import android.graphics.drawable.Drawable;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
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
        border.setPoints(line);
        core.setPoints(line);
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

    /** آدمک در موقعیت فعلی، با دایره دقت GPS. */
    void showMe(double lat, double lon, float accuracy) {
        LatLong at = new LatLong(lat, lon);
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
        view.getLayerManager().redrawLayers();
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
        for (SatelliteLayer layer : satellite) {
            layer.close();
        }
        view.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
    }
}
