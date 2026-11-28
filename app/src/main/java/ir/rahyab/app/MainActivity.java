package ir.rahyab.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity implements LocationListener {
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_IMPORT = 2;
    private static final int REQ_EXPORT = 3;
    private static final int REQ_IMPORT_SATELLITE = 4;
    private static final long FRESH_MS = 10_000;

    private static volatile MapStore.Progress copying;   // پیشرفت کپی فایل
    private String layer;
    private boolean locateRequested;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MapScreen map;
    private LocationManager locationManager;
    private Location lastFix;
    private boolean follow = true;
    private boolean centeredOnce;
    private boolean startAfterPermission;
    private long shownTrack;       // مسیری که روی نقشه است
    private long lastPointId;
    private Point origin;
    private String pendingExport;
    private String mapMessage;
    private int ticks;
    private RotatingFrame frame;
    private DriveAssistant drive;
    private boolean car;
    private boolean headingUp;
    private Point target;           // نقطه‌ای که به سمت آن راهنمایی می‌شود
    private Averager averager;      // فقط وقتی پنجره ذخیره نقطه باز است
    private Runnable averaging;
    private int satellitesUsed = -1;
    private GnssStatus.Callback satellites;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (++ticks % 8 == 0) {
                map.retryTiles();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidGraphicFactory.createInstance(getApplication());
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // دکمه‌ها زیر نوار وضعیت و نوار ناوبری نروند
        findViewById(R.id.overlay).setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        MapView mapView = findViewById(R.id.map);
        map = new MapScreen(this, mapView);
        // لرزش انگشت یا زوم دو انگشتی دنبال‌کردن را قطع نکند؛ فقط کشیدن نقشه
        int slop = ViewConfiguration.get(this).getScaledTouchSlop() * 3;
        float[] down = new float[2];
        boolean[] pinch = new boolean[1];
        mapView.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                down[0] = e.getX();
                down[1] = e.getY();
                pinch[0] = false;
            } else if (e.getPointerCount() > 1) {
                pinch[0] = true;
            } else if (action == MotionEvent.ACTION_MOVE && !pinch[0]
                    && Math.hypot(e.getX() - down[0], e.getY() - down[1]) > slop) {
                follow = false;
            }
            return false;
        });
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        click(R.id.startStop, v -> toggleTracking());
        click(R.id.savePoint, v -> {
            if (!car) {
                savePoint();
            } else if (fresh() && isGps(lastFix)) {
                drive.report(lastFix);
            } else {
                toast("هنوز موقعیت تازه‌ای از GPS دریافت نشده است");
            }
        });
        click(R.id.points, v -> showPoints());
        click(R.id.tracks, v -> showTracks());
        click(R.id.maps, v -> showMapMenu());
        click(R.id.zoomIn, v -> map.zoom(1));
        click(R.id.zoomOut, v -> map.zoom(-1));
        click(R.id.follow, v -> locateMe());
        click(R.id.layers, v -> showLayerMenu());
        click(R.id.compass, v -> toggleCompass());
        click(R.id.target, v -> {
            target = null;
            map.setTarget(null);
            refresh();
        });
        frame = findViewById(R.id.frame);
        CompassView compass = findViewById(R.id.compass);
        frame.setListener(new RotatingFrame.Listener() {
            @Override
            public void rotated(float degrees, boolean byUser) {
                if (byUser) {
                    headingUp = false;
                }
                compass.setAngle(degrees);
                map.setAngle(degrees);
            }

            @Override
            public void rotating(boolean active) {
                map.setRotating(active);
            }
        });
        map.setWaypointListener(this::pointMenu);
        drive = new DriveAssistant(this, map);
        click(R.id.mode, v -> chooseMode());
        satellites = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                int used = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (status.usedInFix(i)) {
                        used++;
                    }
                }
                satellitesUsed = used;
            }
        };
        click(R.id.about, v -> showAbout());
        click(R.id.coords, v -> {
            if (lastFix != null) {
                copy(Geo.coords(lastFix.getLatitude(), lastFix.getLongitude()));
            }
        });
        click(R.id.progressCancel, v -> {
            MapStore.cancel(this);
            mapMessage = null;
            refresh();
        });

        new Thread(() -> TileProvider.trimCache(getApplicationContext())).start();
        layer = TrackingService.prefs(this).getString("layer", "sentinel");
        applyLayer();
        map.showWaypoints(Db.get(this).waypoints());
        applyMode(TrackingService.prefs(this).getBoolean("car", false));
        if (!hasLocationPermission()) {
            askPermission();
        }
    }

    private void click(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(listener);
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onResume() {
        super.onResume();
        if (hasLocationPermission()) {
            // شبکه فقط برای نمایش سریع موقعیت تقریبی است؛ مسیر فقط با GPS ثبت می‌شود
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.getAllProviders().contains(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000, 0, this);
                    Location known = locationManager.getLastKnownLocation(provider);
                    if (known != null && (lastFix == null
                            || known.getElapsedRealtimeNanos() > lastFix.getElapsedRealtimeNanos())) {
                        onLocationChanged(known);
                    }
                }
            }
            locationManager.registerGnssStatusCallback(satellites, handler);
            // اگر سیستم سرویس را بسته باشد، ثبت مسیر را ادامه بده
            if (TrackingService.activeTrack(this) != 0 && !TrackingService.running) {
                beginTracking(null);
            }
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        locationManager.removeUpdates(this);
        locationManager.unregisterGnssStatusCallback(satellites);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        drive.destroy();
        map.destroy();
        super.onDestroy();
    }

    // ---------- موقعیت و مسیر ----------

    @Override
    public void onLocationChanged(Location l) {
        // موقعیت شبکه جای موقعیت تازه GPS را نگیرد
        if (!isGps(l) && lastFix != null && isGps(lastFix) && fresh()) {
            return;
        }
        lastFix = l;
        if (averager != null && isGps(l) && fresh() && averager.add(TrackingService.toPoint(l))) {
            averaging.run();
        }
        // هنگام حرکت، نقشه رو به جلو می‌چرخد؛ تغییرهای ریز نادیده گرفته می‌شوند
        if (headingUp && isGps(l) && l.hasBearing() && l.getSpeed() > (car ? 2f : 0.8f)
                && Math.abs(RotatingFrame.normalize(-l.getBearing() - frame.angle())) > 6) {
            frame.rotateTo(-l.getBearing());
        }
        if (isGps(l)) {
            drive.onLocation(l, fresh());
        }
        // سر خط فقط با موقعیت دقیق GPS دنبال آدمک می‌رود
        map.setLiveTail(recordingShown() && isGps(l) && l.getAccuracy() <= TrackFilter.MAX_ACCURACY);
        if (follow && (!centeredOnce || locateRequested)) {
            map.center(l.getLatitude(), l.getLongitude(), closeZoom());
            centeredOnce = true;
            locateRequested = false;
        }
        map.moveMe(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0, () -> follow);
        refresh();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    // ---------- حالت پیاده و ماشین ----------

    private void chooseMode() {
        new Sheet(this, "حالت حرکت", null)
                .row("🚶", "پیاده", car ? null : "✓ فعال", () -> switchMode(false))
                .row("🚗", "ماشین", car ? "✓ فعال" : "سرعت‌سنج، هشدار دوربین و سرعت‌گیر", () -> switchMode(true))
                .show();
    }

    private void switchMode(boolean toCar) {
        if (toCar == car) {
            return;
        }
        TrackingService.prefs(this).edit().putBoolean("car", toCar).apply();
        applyMode(toCar);
        follow = true;
        if (lastFix != null) {
            map.center(lastFix.getLatitude(), lastFix.getLongitude(), closeZoom());
        }
        toast(toCar ? "حالت ماشین" : "حالت پیاده");
    }

    private void applyMode(boolean toCar) {
        car = toCar;
        headingUp = toCar;
        if (!toCar) {
            frame.rotateTo(0);
        }
        ((ImageView) findViewById(R.id.mode)).setImageResource(toCar ? R.drawable.ic_car : R.drawable.ic_walk);
        ((Button) findViewById(R.id.savePoint)).setText(toCar ? "⚠ گزارش خطر" : "📍 ذخیره مختصات");
        map.setVehicle(toCar);
        drive.setActive(toCar, lastFix != null && isGps(lastFix) ? lastFix : null);
    }

    /** لمس قطب‌نما: اگر نقشه چرخیده شمال بالا؛ وگرنه جهت حرکت بالا. */
    private void toggleCompass() {
        if (headingUp || Math.abs(frame.angle()) > 1) {
            headingUp = false;
            frame.rotateTo(0);
            toast("شمال بالای نقشه");
        } else {
            headingUp = true;
            toast("هنگام حرکت، نقشه رو به جلو می‌چرخد");
        }
    }

    private void fitRoute(List<Point> points) {
        follow = false;
        headingUp = false;
        frame.rotateTo(0);
        map.fit(points, closeZoom(), frame.getWidth(), frame.getHeight());
    }

    private boolean recordingShown() {
        long active = TrackingService.activeTrack(this);
        return active != 0 && active == shownTrack;
    }

    /** آخرین موقعیت را به عنوان نقطه دقیق مقصد ثبت می‌کند. */
    private void addEndPoint(long id) {
        if (!fresh() || !isGps(lastFix) || lastFix.getAccuracy() > TrackFilter.MAX_ACCURACY) {
            return;
        }
        Db db = Db.get(this);
        List<Point> points = db.points(id, 0);
        Db.Track track = db.track(id);
        if (points.isEmpty() || track == null) {
            return;
        }
        Point last = points.get(points.size() - 1);
        Point end = TrackingService.toPoint(lastFix);
        double step = Geo.distance(last.lat, last.lon, end.lat, end.lon);
        // نوسان هنگام ایستادن مسافت نسازد و پرش دور پذیرفته نشود
        if (step >= Math.max(TrackFilter.MIN_STEP, end.accuracy * 0.35) && step < 100) {
            db.addPoint(id, end, track.distance + step);
        }
    }

    private static boolean isGps(Location l) {
        return LocationManager.GPS_PROVIDER.equals(l.getProvider());
    }

    /** دکمه «مکان من»: آدمک را وسط صفحه می‌آورد. */
    private void locateMe() {
        if (!hasLocationPermission()) {
            askPermission();
            return;
        }
        follow = true;
        if (lastFix == null) {
            locateRequested = true;
            toast("در حال یافتن مکان شما…");
        } else {
            map.center(lastFix.getLatitude(), lastFix.getLongitude(), closeZoom());
            if (!fresh()) {
                locateRequested = true;
                toast("آخرین مکان شناخته‌شده؛ در حال به‌روزرسانی…");
            }
        }
    }

    private int closeZoom() {
        // تصویر رایگان ۱۰ متری در زوم خیلی نزدیک تار می‌شود
        return "sentinel".equals(layer) ? 15 : 17;
    }

    private boolean fresh() {
        return lastFix != null
                && (SystemClock.elapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1_000_000 < FRESH_MS;
    }

    private void refresh() {
        Db db = Db.get(this);
        long active = TrackingService.activeTrack(this);
        if (active != 0 && active != shownTrack) {
            showTrack(active);
        }
        Db.Track track = shownTrack == 0 ? null : db.track(shownTrack);
        if (track != null && track.finish == 0) {
            List<Point> added = db.points(shownTrack, lastPointId);
            if (!added.isEmpty()) {
                if (origin == null) {
                    origin = added.get(0);
                }
                lastPointId = added.get(added.size() - 1).id;
                map.extendTrack(added, false);
            }
        }

        double distance = track == null ? 0 : track.distance;
        long time = track == null ? 0
                : (track.finish == 0 ? System.currentTimeMillis() : track.finish) - track.start;
        if (!fresh()) {
            drive.onSignalLost();
        }
        text(R.id.distance, Geo.length(distance));
        text(R.id.duration, Geo.duration(time));
        float kmh = fresh() && lastFix.hasSpeed() ? lastFix.getSpeed() * 3.6f : 0;
        text(R.id.speed, String.format(java.util.Locale.US, "%.1f km/h", kmh));
        text(R.id.toStart, (origin == null || lastFix == null ? "—"
                : Geo.length(Geo.distance(origin.lat, origin.lon, lastFix.getLatitude(), lastFix.getLongitude()))));
        text(R.id.coords, lastFix == null ? "—" : "⧉  " + Geo.coords(lastFix.getLatitude(), lastFix.getLongitude()));
        text(R.id.altitude, fresh() && lastFix.hasAltitude() ? Math.round(lastFix.getAltitude()) + " m" : "—");
        text(R.id.average, time > 10_000
                ? String.format(java.util.Locale.US, "%.1f km/h", distance / (time / 1000.0) * 3.6) : "—");
        text(R.id.satellites, fresh() && satellitesUsed >= 0 ? String.valueOf(satellitesUsed) : "—");
        findViewById(R.id.target).setVisibility(target == null ? View.GONE : View.VISIBLE);
        if (target != null) {
            String away = "…";
            if (lastFix != null) {
                double d = Geo.distance(lastFix.getLatitude(), lastFix.getLongitude(), target.lat, target.lon);
                away = "\u200E" + Geo.length(d) + "\u200F  به سمت " + Geo.direction(
                        Geo.bearing(lastFix.getLatitude(), lastFix.getLongitude(), target.lat, target.lon));
            }
            text(R.id.target, "🎯 " + target.name + ": " + away + "   ✕");
        }

        String gps;
        if (!hasLocationPermission()) {
            gps = "مجوز موقعیت دقیق داده نشده است";
        } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gps = "GPS خاموش است";
        } else if (!fresh()) {
            gps = "در حال جستجوی ماهواره… زیر آسمان باز بایستید";
        } else {
            gps = (isGps(lastFix) ? "● دقت GPS: ±" : "مکان تقریبی شبکه: ±")
                    + Math.round(lastFix.getAccuracy()) + " متر";
            if (lastFix.getAccuracy() > TrackFilter.MAX_ACCURACY) {
                gps += " (ضعیف؛ در خط مسیر ثبت نمی‌شود)";
            }
        }
        MapStore.Progress download = MapStore.poll(this, () -> setLayer("vector"));
        MapStore.Progress progress = download != null && download.active ? download : copying;
        if (download != null && !download.active) {
            mapMessage = download.text;
        }
        findViewById(R.id.progressCard).setVisibility(progress == null ? View.GONE : View.VISIBLE);
        if (progress != null) {
            text(R.id.progressText, progress.text);
            ProgressBar bar = findViewById(R.id.progressBar);
            bar.setIndeterminate(progress.percent < 0);
            bar.setProgress(Math.max(0, progress.percent));
            findViewById(R.id.progressCancel).setVisibility(progress == copying ? View.GONE : View.VISIBLE);
        }
        text(R.id.status, mapMessage == null ? gps : gps + "\n" + mapMessage);

        Button b = findViewById(R.id.startStop);
        b.setText(active != 0 ? "■\nپایان" : "▶\nشروع");
        b.setBackgroundResource(active != 0 ? R.drawable.bg_stop : R.drawable.bg_start);
    }

    private void text(int id, String value) {
        TextView v = findViewById(id);
        if (!value.contentEquals(v.getText())) {
            v.setText(value);
        }
    }

    private void showTrack(long id) {
        map.clearTrack();
        shownTrack = id;
        lastPointId = 0;
        origin = null;
        Db.Track t = Db.get(this).track(id);
        if (t == null) {
            return;
        }
        List<Point> points = Db.get(this).points(id, 0);
        if (!points.isEmpty()) {
            origin = points.get(0);
            lastPointId = points.get(points.size() - 1).id;
            map.extendTrack(points, t.finish != 0);
        }
    }

    private void toggleTracking() {
        long active = TrackingService.activeTrack(this);
        if (active != 0) {
            new AlertDialog.Builder(this)
                    .setMessage("ثبت این مسیر تمام شود؟")
                    .setPositiveButton("بله، مقصد", (d, w) -> {
                        addEndPoint(active);
                        TrackingService.finish(this);
                        stopService(new Intent(this, TrackingService.class));
                        map.setLiveTail(false);
                        showTrack(active);
                        fitRoute(Db.get(this).points(active, 0));
                        refresh();
                    })
                    .setNegativeButton("ادامه حرکت", null)
                    .show();
            return;
        }
        if (!hasLocationPermission()) {
            startAfterPermission = true;
            askPermission();
        } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("ابتدا GPS را روشن کنید");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else {
            follow = true;
            if (fresh() && isGps(lastFix) && lastFix.getAccuracy() <= TrackFilter.MAX_ACCURACY) {
                beginTracking(lastFix);
                map.center(lastFix.getLatitude(), lastFix.getLongitude(), closeZoom());
            } else {
                new AlertDialog.Builder(this)
                        .setMessage("GPS هنوز مکان دقیق شما را پیدا نکرده است. اگر الان شروع کنید، خط از اولین مکان دقیق رسم می‌شود.")
                        .setPositiveButton("صبر می‌کنم", null)
                        .setNegativeButton("همین حالا شروع کن", (d, w) -> beginTracking(null))
                        .show();
            }
        }
    }

    private void beginTracking(Location from) {
        Intent i = new Intent(this, TrackingService.class);
        if (from != null) {
            i.putExtra("lat", from.getLatitude()).putExtra("lon", from.getLongitude())
                    .putExtra("acc", from.getAccuracy()).putExtra("time", from.getTime());
            if (from.hasAltitude()) {
                i.putExtra("alt", from.getAltitude());
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ---------- مجوزها ----------

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(list.toArray(new String[0]), REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQ_PERMISSION) {
            return;
        }
        if (hasLocationPermission()) {
            // onResume پس از این فراخوانی، دریافت موقعیت را آغاز می‌کند
            if (startAfterPermission) {
                startAfterPermission = false;
                handler.post(this::toggleTracking);
            }
        } else {
            new AlertDialog.Builder(this)
                    .setMessage("برای کار برنامه، مجوز «موقعیت دقیق» را از تنظیمات فعال کنید.")
                    .setPositiveButton("تنظیمات", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null))))
                    .setNegativeButton("بعداً", null)
                    .show();
        }
    }

    // ---------- نقاط ذخیره‌شده ----------

    /** تا وقتی پنجره باز است، موقعیت‌ها میانگین گرفته می‌شوند تا نقطه دقیق‌تر شود. */
    private void savePoint() {
        if (!fresh() || !isGps(lastFix)) {
            toast("هنوز موقعیت تازه‌ای از GPS دریافت نشده است");
            return;
        }
        // مختصات لحظه لمس دکمه؛ اگر دقت ضعیف باشد همین ذخیره می‌شود
        Point atPress = TrackingService.toPoint(lastFix);
        Averager samples = new Averager();
        samples.add(atPress);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(this);
        info.setTextSize(14);
        EditText name = new EditText(this);
        name.setText("نقطه " + (Db.get(this).waypoints().size() + 1));
        name.setSelectAllOnFocus(true);
        box.addView(info);
        box.addView(name);

        averager = samples;
        averaging = () -> {
            Point p = samples.result() == null ? atPress : samples.result();
            info.setText("\u200E" + Geo.coords(p.lat, p.lon) + "\nدقت: ±" + Math.round(p.accuracy)
                    + " متر | نمونه‌ها: " + samples.count + "\nبرای دقت بیشتر چند ثانیه بی‌حرکت بمانید.");
        };
        averaging.run();
        new AlertDialog.Builder(this)
                .setTitle("📍 ذخیره مختصات")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    Point p = samples.result() == null ? atPress : samples.result();
                    Db.get(this).addWaypoint(name.getText().toString().trim(), p);
                    map.showWaypoints(Db.get(this).waypoints());
                    toast("ذخیره شد");
                })
                .setNegativeButton("انصراف", null)
                .setOnDismissListener(d -> {
                    if (averager == samples) {
                        averager = null;
                    }
                })
                .show();
    }

    private void showPoints() {
        List<Point> points = Db.get(this).waypoints();
        if (points.isEmpty()) {
            toast("هنوز نقطه‌ای ذخیره نشده است");
            return;
        }
        Sheet sheet = new Sheet(this, "نقاط ذخیره‌شده", points.size() + " نقطه");
        sheet.row("📤", "خروجی همه نقاط", "اکسل، GPX یا گوگل ارث", () -> new Sheet(this, "خروجی نقاط", null)
                .row("📊", "اکسل (CSV)", null, () -> export("points.csv", "text/csv", Geo.csv(points)))
                .row("🧭", "GPX", null, () -> export("points.gpx", "application/gpx+xml",
                        Geo.gpx(Collections.<Point>emptyList(), points)))
                .row("🌍", "گوگل ارث (KML)", null, () -> export("points.kml",
                        "application/vnd.google-earth.kml+xml", Geo.kml(Collections.<Point>emptyList(), points)))
                .show());
        for (Point p : points) {
            sheet.row("📍", p.name, "\u200E" + Geo.coords(p.lat, p.lon), () -> pointMenu(p));
        }
        sheet.show();
    }

    private void pointMenu(Point p) {
        String c = Geo.coords(p.lat, p.lon);
        StringBuilder detail = new StringBuilder("\u200E" + c + "\n\u200Fدقت ±" + Math.round(p.accuracy) + " متر");
        if (!Double.isNaN(p.alt)) {
            detail.append(" | ارتفاع ").append(Math.round(p.alt)).append(" متر");
        }
        if (lastFix != null) {
            detail.append("\nفاصله تا شما: \u200E").append(Geo.length(
                    Geo.distance(lastFix.getLatitude(), lastFix.getLongitude(), p.lat, p.lon)));
        }
        new Sheet(this, p.name, detail.toString())
                .row("📋", "کپی مختصات", null, () -> copy(c))
                .row("🎯", "راهنمایی تا این نقطه", "خط‌چین، فاصله و سمت مستقیم", () -> {
                    target = p;
                    map.setTarget(p);
                    refresh();
                })
                .row("🗺", "نمایش روی نقشه", null, () -> {
                    follow = false;
                    map.center(p.lat, p.lon, closeZoom());
                })
                .row("🌍", "باز کردن در برنامه نقشه گوشی", "مثل گوگل مپ (نیازمند همان برنامه)",
                        () -> openExternal(p.lat, p.lon, p.name))
                .row("✉", "ارسال برای دیگران", null, () -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, p.name + "\n" + c);
                    startActivity(Intent.createChooser(send, "ارسال"));
                })
                .row("🗑", "حذف", null, () -> confirm("این نقطه حذف شود؟", () -> {
                    Db.get(this).deleteWaypoint(p.id);
                    if (target != null && target.id == p.id) {
                        target = null;
                        map.setTarget(null);
                    }
                    map.showWaypoints(Db.get(this).waypoints());
                }))
                .show();
    }

    private void openExternal(double lat, double lon, String name) {
        String c = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon);
        view("geo:" + c + "?q=" + c + "(" + Uri.encode(name) + ")", Intent.ACTION_VIEW);
    }

    // ---------- تاریخچه مسیرها ----------

    private void showTracks() {
        List<Db.Track> tracks = Db.get(this).allTracks();
        if (tracks.isEmpty()) {
            toast("هنوز مسیری ثبت نشده است");
            return;
        }
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        Sheet sheet = new Sheet(this, "مسیرهای من", tracks.size() + " مسیر");
        for (Db.Track t : tracks) {
            String when = format.format(new Date(t.start));
            String info = Geo.length(t.distance)
                    + (t.finish == 0 ? " (در حال ثبت)" : "  |  " + Geo.duration(t.finish - t.start));
            sheet.row("🥾", when, "\u200E" + info, () -> trackMenu(t, when));
        }
        sheet.show();
    }

    private void trackMenu(Db.Track t, String title) {
        new Sheet(this, title, "\u200E" + Geo.length(t.distance))
                .row("🗺", "نمایش کل مسیر روی نقشه", null, () -> {
                    if (TrackingService.activeTrack(this) != 0 && t.finish != 0) {
                        toast("ابتدا حرکت فعلی را تمام کنید");
                        return;
                    }
                    showTrack(t.id);
                    fitRoute(Db.get(this).points(t.id, 0));
                })
                .row("🌍", "خروجی گوگل ارث (KML)", "مسیر را در گوگل ارث باز کنید", () -> export(
                        "track-" + t.id + ".kml", "application/vnd.google-earth.kml+xml",
                        Geo.kml(Db.get(this).points(t.id, 0), Collections.<Point>emptyList())))
                .row("🧭", "خروجی GPX", null, () -> export("track-" + t.id + ".gpx", "application/gpx+xml",
                        Geo.gpx(Db.get(this).points(t.id, 0), Collections.<Point>emptyList())))
                .row("🗑", "حذف", null, () -> {
                    Db.Track now = Db.get(this).track(t.id);
                    if (now == null || now.finish == 0) {
                        toast("مسیر در حال ثبت حذف نمی‌شود");
                        return;
                    }
                    confirm("این مسیر حذف شود؟", () -> {
                        Db.get(this).deleteTrack(t.id);
                        if (shownTrack == t.id) {
                            map.clearTrack();
                            shownTrack = 0;
                            origin = null;
                        }
                    });
                })
                .show();
    }

    // ---------- نوع نقشه ----------

    private void setLayer(String value) {
        layer = value;
        TrackingService.prefs(this).edit().putString("layer", value).apply();
        applyLayer();
    }

    private void applyLayer() {
        mapMessage = null;
        String credit = "";
        try {
            File satellite = MapStore.satelliteFile(this);
            if ("mbtiles".equals(layer) && satellite != null) {
                map.loadSatellite(TileProvider.mbtiles(satellite));
                credit = "Offline imagery file";
            } else if ("vector".equals(layer)) {
                File file = MapStore.current(this);
                if (file == null) {
                    mapMessage = "نقشه خیابانی آفلاین ندارید؛ «نقشه آفلاین» را بزنید";
                } else {
                    map.loadMap(file);
                    credit = "© OpenStreetMap contributors";
                }
            } else {
                map.loadSatellite(TileProvider.sentinel(this));
                credit = "Sentinel-2 cloudless 2024 by EOX (Copernicus data) CC BY-NC-SA 4.0";
            }
        } catch (RuntimeException e) {
            mapMessage = "فایل نقشه باز نشد؛ دوباره دانلود یا انتخاب کنید";
        }
        text(R.id.credit, credit);
    }

    private void showLayerMenu() {
        new Sheet(this, "نوع نقشه", null)
                .row("🛰", "ماهواره‌ای رایگان", "جزئیات کم (دقت ۱۰ متر)", () -> setLayer("sentinel"))
                .row("💾", "ماهواره‌ای آفلاین", "از فایل MBTiles شما", () -> {
                    if (MapStore.satelliteFile(this) == null) {
                        pickSatelliteFile();
                    } else {
                        setLayer("mbtiles");
                    }
                })
                .row("🗺", "نقشه خیابانی آفلاین", "واضح در هر زوم", () -> setLayer("vector"))
                .row("🌍", "این محل در برنامه نقشه گوشی", "برای دیدن تصویر ماهواره‌ای در برنامه دیگر", () ->
                        openExternal(map.center().latitude, map.center().longitude, "Rahyab"))
                .show();
    }

    private void pickSatelliteFile() {
        if (copying != null) {
            toast("یک فایل در حال کپی است");
            return;
        }
        open(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT_SATELLITE);
    }

    // ---------- نقشه آفلاین ----------

    private void showMapMenu() {
        if (MapStore.downloading(this) || copying != null) {
            toast("یک دانلود در جریان است");
            return;
        }
        new Sheet(this, "نقشه آفلاین", "برای استفاده بدون اینترنت")
                .row("⬇", "دانلود نقشه خیابانی ایران", "حدود ۳۰۰ مگابایت", () -> download(MapStore.IRAN_URL))
                .row("🌐", "دانلود از نشانی دلخواه", "کشور دیگر", () -> {
                    EditText url = new EditText(this);
                    url.setText("https://download.mapsforge.org/maps/v5/");
                    new AlertDialog.Builder(this)
                            .setTitle("نشانی کامل فایل ‎.map")
                            .setView(url)
                            .setPositiveButton("دانلود", (d, w) -> download(url.getText().toString().trim()))
                            .setNegativeButton("انصراف", null)
                            .show();
                })
                .row("📂", "انتخاب فایل ‎.map از گوشی", null, () -> open(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT))
                .row("🛰", "انتخاب تصویر ماهواره‌ای آفلاین", "فایل MBTiles", this::pickSatelliteFile)
                .show();
    }

    // ---------- درباره ما ----------

    private void showAbout() {
        new Sheet(this, "رهیاب — نسخه ۴", "برنامه‌نویس: عباس رضایی")
                .row("📞", "تماس با پشتیبانی", "\u200E09118655135",
                        () -> view("tel:09118655135", Intent.ACTION_DIAL))
                .row("📸", "اینستاگرام", "\u200Eabbas.rezaeii",
                        () -> view("https://www.instagram.com/abbas.rezaeii/", Intent.ACTION_VIEW))
                .show();
    }

    private void view(String uri, String action) {
        try {
            startActivity(new Intent(action, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            toast("برنامه مناسبی پیدا نشد");
        }
    }

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("coords", text));
        toast("مختصات کپی شد");
    }

    private void download(String url) {
        try {
            MapStore.download(this, url);
            toast("دانلود آغاز شد");
        } catch (RuntimeException e) {
            toast("شروع دانلود ممکن نشد؛ نشانی را بررسی کنید");
        }
    }

    // ---------- فایل‌ها ----------

    private void export(String name, String type, String content) {
        pendingExport = content;
        open(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type).putExtra(Intent.EXTRA_TITLE, name), REQ_EXPORT);
    }

    private void open(Intent intent, int code) {
        try {
            startActivityForResult(intent, code);
        } catch (ActivityNotFoundException e) {
            toast("برنامه مدیریت فایل پیدا نشد");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (code == REQ_EXPORT && pendingExport != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                toast("فایل ذخیره شد");
            } catch (IOException | RuntimeException e) {
                toast("ذخیره فایل ناموفق بود");
            }
            pendingExport = null;
        } else if (code == REQ_IMPORT || code == REQ_IMPORT_SATELLITE) {
            boolean mbtiles = code == REQ_IMPORT_SATELLITE;
            Context app = getApplicationContext();
            copying = MapStore.describe("در حال کپی نقشه", 0, -1);
            new Thread(() -> {
                boolean ok = false;
                try {
                    MapStore.importFile(app, uri, mbtiles,
                            (got, all) -> copying = MapStore.describe("در حال کپی نقشه", got, all));
                    ok = true;
                } catch (IOException | RuntimeException e) {
                    ok = false;
                } finally {
                    copying = null;
                }
                boolean success = ok;
                handler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (success) {
                        setLayer(mbtiles ? "mbtiles" : "vector");
                    }
                    toast(success ? "نقشه آماده شد" : "این فایل نقشه معتبر نیست");
                });
            }).start();
        }
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("بله", (d, w) -> yes.run())
                .setNegativeButton("خیر", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
