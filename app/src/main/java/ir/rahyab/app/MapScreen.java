package ir.rahyab.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
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
import java.util.function.Consumer;

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
    private List<Point> waypoints = new ArrayList<>();
    private Consumer<Point> waypointTap;
    private Polyline guide;
    private LatLong target;
    private float angle;
    private float iconAngle;
    private boolean paused;         // هنگام چرخش، نقشه دوباره رسم نمی‌شود
    private final List<Layer> hazardLayers = new ArrayList<>();
    private List<Hazard> hazards = new ArrayList<>();
    private Consumer<Hazard> hazardTap;
    private boolean car;
    private float heading;

    MapScreen(Context context, MapView view) {
        this.context = context;
        this.view = view;
        // خط‌کش داخلی در نقشه چرخان بیرون صفحه می‌افتد
        view.getMapScaleBar().setVisible(false);
        view.getModel().frameBufferModel.setOverdrawFactor(1.0);
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
            layer.setRedraw(this::redraw);
            satellite.add(layer);
        }
        redraw();
    }

    /** وقتی کاشی‌ای ناموفق مانده، دوباره رسم می‌کند تا تلاش تازه انجام شود. */
    void retryTiles() {
        for (SatelliteLayer layer : satellite) {
            if (layer.hasFailures()) {
                redraw();
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
        redraw();
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
        redraw();
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
        redraw();
    }

    void setWaypointListener(Consumer<Point> listener) {
        waypointTap = listener;
    }

    /** نقاط ذخیره‌شده با پین و نام؛ لمس آن‌ها مختصات را نشان می‌دهد. */
    void showWaypoints(List<Point> points) {
        waypoints = points;
        for (Layer l : wayLayers) {
            view.getLayerManager().getLayers().remove(l);
        }
        wayLayers.clear();
        for (Point p : points) {
            add(wayLayers, pin(p));
        }
        redraw();
    }

    void setAngle(float degrees) {
        angle = degrees;
    }

    /** پس از پایان چرخش، آیکون‌ها یک بار ایستاده و نقشه دوباره رسم می‌شود. */
    void setRotating(boolean active) {
        paused = active;
        if (active) {
            return;
        }
        if (Math.abs(RotatingFrame.normalize(angle - iconAngle)) >= 6 || (angle == 0 && iconAngle != 0)) {
            iconAngle = angle;
            showWaypoints(waypoints);
            showHazards(hazards);
            refreshMe();
        }
        redraw();
    }

    private void redraw() {
        if (!paused) {
            view.getLayerManager().redrawLayers();
        }
    }

    private void refreshMe() {
        if (me != null) {
            view.getLayerManager().getLayers().remove(me);
            me = meMarker(shown);
            view.getLayerManager().getLayers().add(me);
        }
    }

    /** آیکون موقعیت: آدمک در حالت پیاده، خودرو در حالت ماشین. */
    void setVehicle(boolean on) {
        car = on;
        refreshMe();
        redraw();
    }

    /** جهت حرکت خودرو بر حسب درجه از شمال. */
    void setHeading(float degrees) {
        if (Math.abs(RotatingFrame.normalize(degrees - heading)) >= 5) {
            heading = degrees;
            if (car) {
                refreshMe();
                redraw();
            }
        }
    }

    private Marker meMarker(LatLong at) {
        if (!car) {
            return personMarker(at);
        }
        Drawable drawable = context.getDrawable(R.drawable.car);
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        int size = (int) Math.ceil(Math.hypot(w, h));
        android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(icon);
        canvas.rotate(heading, size / 2f, size / 2f);
        drawable.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2);
        drawable.draw(canvas);
        return new Marker(at, AndroidGraphicFactory.convertToBitmap(
                new BitmapDrawable(context.getResources(), icon)), 0, 0);
    }

    void setHazardListener(Consumer<Hazard> listener) {
        hazardTap = listener;
    }

    /** نشانه دوربین‌ها و سرعت‌گیرهای نزدیک. */
    void showHazards(List<Hazard> list) {
        hazards = list;
        for (Layer l : hazardLayers) {
            view.getLayerManager().getLayers().remove(l);
        }
        hazardLayers.clear();
        for (int i = 0; i < Math.min(80, list.size()); i++) {
            Hazard h = list.get(i);
            Drawable drawable = context.getDrawable(h.type == Hazard.CAMERA
                    ? R.drawable.hazard_camera : R.drawable.hazard_bump);
            int w = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(w, height,
                    android.graphics.Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, w, height);
            drawable.draw(new android.graphics.Canvas(icon));
            Upright placed = upright(icon);
            float reach = height / 2f;
            add(hazardLayers, new Marker(new LatLong(h.lat, h.lon), placed.bitmap, placed.dx, placed.dy) {
                @Override
                public boolean onTap(LatLong tapLatLong, org.mapsforge.core.model.Point layerXY,
                                     org.mapsforge.core.model.Point tapXY) {
                    double turn = Math.toRadians(-iconAngle);
                    double x = layerXY.x + reach * Math.sin(turn);
                    double y = layerXY.y - reach * Math.cos(turn);
                    if (hazardTap != null && Math.hypot(tapXY.x - x, tapXY.y - y) <= reach * 1.3) {
                        hazardTap.accept(h);
                        return true;
                    }
                    return false;
                }
            });
        }
        redraw();
    }

    /** خط‌چین راهنما از آدمک تا نقطه هدف؛ null یعنی خاموش. */
    void setTarget(Point p) {
        target = p == null ? null : new LatLong(p.lat, p.lon);
        if (guide != null) {
            view.getLayerManager().getLayers().remove(guide);
            guide = null;
        }
        if (target != null) {
            Paint dashed = paint(0xFF00E5FF, 4);
            float d = context.getResources().getDisplayMetrics().density;
            dashed.setDashPathEffect(new float[]{12 * d, 8 * d});
            guide = new Polyline(dashed, AndroidGraphicFactory.INSTANCE);
            add(new ArrayList<>(), guide);
            drawGuide();
        }
        redraw();
    }

    private void drawGuide() {
        if (guide != null) {
            List<LatLong> points = new ArrayList<>();
            if (shown != null) {
                points.add(shown);
                points.add(target);
            }
            guide.setPoints(points);
        }
    }

    LatLong center() {
        return view.getModel().mapViewPosition.getCenter();
    }

    private Marker pin(Point p) {
        float d = context.getResources().getDisplayMetrics().density;
        android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(12 * d);
        text.setTextAlign(android.graphics.Paint.Align.CENTER);
        text.setFakeBoldText(true);
        String name = p.name == null ? "" : p.name;
        if (name.length() > 18) {
            name = name.substring(0, 17) + "…";
        }
        float labelWidth = name.isEmpty() ? 0 : text.measureText(name) + 14 * d;
        int w = (int) Math.ceil(Math.max(34 * d, labelWidth));
        int h = (int) Math.ceil(64 * d);
        android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(w, h,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(icon);
        if (!name.isEmpty()) {
            text.setColor(0xE60F1B24);
            canvas.drawRoundRect(new RectF((w - labelWidth) / 2, 0, (w + labelWidth) / 2, 20 * d),
                    10 * d, 10 * d, text);
            text.setColor(0xFFFFFFFF);
            canvas.drawText(name, w / 2f, 14.5f * d, text);
        }
        Drawable drawable = context.getDrawable(R.drawable.pin);
        int pw = (int) (30 * d);
        int ph = (int) (40 * d);
        drawable.setBounds((w - pw) / 2, h - ph, (w + pw) / 2, h);
        drawable.draw(canvas);
        float reach = 22 * d;
        Upright placed = upright(icon);
        return new Marker(new LatLong(p.lat, p.lon), placed.bitmap, placed.dx, placed.dy) {
            @Override
            public boolean onTap(LatLong tapLatLong, org.mapsforge.core.model.Point layerXY,
                                 org.mapsforge.core.model.Point tapXY) {
                // مرکز پین در دستگاه مختصات نقشه چرخیده
                double turn = Math.toRadians(-iconAngle);
                double x = layerXY.x + reach * Math.sin(turn);
                double y = layerXY.y - reach * Math.cos(turn);
                if (waypointTap != null && Math.hypot(tapXY.x - x, tapXY.y - y) <= reach * 1.3) {
                    waypointTap.accept(p);
                    return true;
                }
                return false;
            }
        };
    }

    private Upright personIcon() {
        Drawable drawable = context.getDrawable(R.drawable.person);
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(w, h,
                android.graphics.Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(new android.graphics.Canvas(icon));
        return upright(icon);
    }

    /** نشانگری که پایینِ وسطِ آیکون روی مختصات است و خلاف چرخش نقشه می‌چرخد. */
    private static final class Upright {
        final Bitmap bitmap;
        final int dx;
        final int dy;

        Upright(Bitmap bitmap, int dx, int dy) {
            this.bitmap = bitmap;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private Upright upright(android.graphics.Bitmap icon) {
        float halfWidth = icon.getWidth() / 2f;
        Matrix turn = new Matrix();
        turn.setRotate(-iconAngle);
        // کادر آیکون چرخیده نسبت به نقطه اتکا
        RectF box = new RectF(-halfWidth, -icon.getHeight(), halfWidth, 0);
        turn.mapRect(box);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                Math.max(1, (int) Math.ceil(box.width())), Math.max(1, (int) Math.ceil(box.height())),
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.translate(-box.left, -box.top);
        canvas.rotate(-iconAngle);
        canvas.drawBitmap(icon, -halfWidth, -icon.getHeight(),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return new Upright(AndroidGraphicFactory.convertToBitmap(
                new BitmapDrawable(context.getResources(), out)),
                Math.round(box.centerX()), Math.round(box.centerY()));
    }

    private Marker personMarker(LatLong at) {
        Upright icon = personIcon();
        return new Marker(at, icon.bitmap, icon.dx, icon.dy);
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
            redraw();
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
            // پای آدمک روی خود نقطه قرار می‌گیرد
            me = meMarker(at);
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
        drawGuide();
        if (follow && !paused) {
            view.setCenter(at);
        }
        redraw();
    }

    /** کل مسیر از مبدأ تا مقصد را در صفحه جا می‌دهد. */
    void fit(List<Point> points, int maxZoom, int width, int height) {
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
        int zoom = maxZoom;
        if (width > 0 && height > 0 && (north > south || east > west)) {
            // فقط بخش میانی صفحه آزاد است؛ پنل‌های بالا و پایین را کنار می‌گذاریم
            Dimension free = new Dimension(Math.max(1, width * 3 / 4), Math.max(1, height / 2));
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
