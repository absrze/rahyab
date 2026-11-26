package ir.rahyab.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
    private static final long FRESH_MS = 10_000;

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

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
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
        mapView.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                follow = false;
            }
            return false;
        });
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        click(R.id.startStop, v -> toggleTracking());
        click(R.id.savePoint, v -> savePoint());
        click(R.id.points, v -> showPoints());
        click(R.id.tracks, v -> showTracks());
        click(R.id.maps, v -> showMapMenu());
        click(R.id.zoomIn, v -> map.zoom(1));
        click(R.id.zoomOut, v -> map.zoom(-1));
        click(R.id.follow, v -> {
            follow = true;
            if (lastFix != null) {
                map.center(lastFix.getLatitude(), lastFix.getLongitude(), true);
            }
        });

        loadMap();
        map.showWaypoints(Db.get(this).waypoints());
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
            if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            }
            // اگر سیستم سرویس را بسته باشد، ثبت مسیر را ادامه بده
            if (TrackingService.activeTrack(this) != 0 && !TrackingService.running) {
                startService();
            }
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        locationManager.removeUpdates(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        map.destroy();
        super.onDestroy();
    }

    // ---------- موقعیت و مسیر ----------

    @Override
    public void onLocationChanged(Location l) {
        lastFix = l;
        map.showMe(l.getLatitude(), l.getLongitude());
        if (follow) {
            map.center(l.getLatitude(), l.getLongitude(), !centeredOnce);
            centeredOnce = true;
        }
        refresh();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

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
        text(R.id.distance, "مسافت\n" + Geo.length(distance));
        text(R.id.duration, "زمان\n" + Geo.duration(time));
        float kmh = fresh() && lastFix.hasSpeed() ? lastFix.getSpeed() * 3.6f : 0;
        text(R.id.speed, "سرعت\n" + String.format(java.util.Locale.US, "%.1f km/h", kmh));
        text(R.id.toStart, "تا مبدأ\n" + (origin == null || lastFix == null ? "—"
                : Geo.length(Geo.distance(origin.lat, origin.lon, lastFix.getLatitude(), lastFix.getLongitude()))));
        text(R.id.coords, lastFix == null ? "—" : Geo.coords(lastFix.getLatitude(), lastFix.getLongitude()));

        String gps;
        if (!hasLocationPermission()) {
            gps = "مجوز موقعیت دقیق داده نشده است";
        } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gps = "GPS خاموش است";
        } else if (!fresh()) {
            gps = "در حال جستجوی ماهواره… زیر آسمان باز بایستید";
        } else {
            gps = "دقت GPS: ±" + Math.round(lastFix.getAccuracy()) + " متر";
            if (lastFix.getAccuracy() > TrackFilter.MAX_ACCURACY) {
                gps += " (ضعیف؛ در خط مسیر ثبت نمی‌شود)";
            }
        }
        String download = MapStore.poll(this, this::loadMap);
        if (download != null) {
            mapMessage = download;
        } else if (MapStore.current(this) != null) {
            mapMessage = null;
        }
        text(R.id.status, mapMessage == null ? gps : gps + "\n" + mapMessage);

        Button b = findViewById(R.id.startStop);
        b.setText(active != 0 ? "■ پایان حرکت" : "▶ شروع حرکت");
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
                        TrackingService.finish(this);
                        stopService(new Intent(this, TrackingService.class));
                        showTrack(active);
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
            startService();
        }
    }

    private void startService() {
        Intent i = new Intent(this, TrackingService.class);
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

    private void savePoint() {
        if (!fresh()) {
            toast("هنوز موقعیت تازه‌ای از GPS دریافت نشده است");
            return;
        }
        // مختصات همان لحظه لمس دکمه
        Point p = TrackingService.toPoint(lastFix);
        EditText name = new EditText(this);
        name.setText("نقطه " + (Db.get(this).waypoints().size() + 1));
        name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(Geo.coords(p.lat, p.lon) + "  (±" + Math.round(p.accuracy) + " m)")
                .setView(name)
                .setPositiveButton("ذخیره", (d, w) -> {
                    Db.get(this).addWaypoint(name.getText().toString().trim(), p);
                    map.showWaypoints(Db.get(this).waypoints());
                    toast("ذخیره شد");
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showPoints() {
        List<Point> points = Db.get(this).waypoints();
        if (points.isEmpty()) {
            toast("هنوز نقطه‌ای ذخیره نشده است");
            return;
        }
        String[] items = new String[points.size()];
        for (int i = 0; i < items.length; i++) {
            Point p = points.get(i);
            items[i] = p.name + "\n‎" + Geo.coords(p.lat, p.lon);
        }
        new AlertDialog.Builder(this)
                .setTitle("نقاط ذخیره‌شده")
                .setItems(items, (d, i) -> pointMenu(points.get(i)))
                .setPositiveButton("خروجی اکسل (CSV)", (d, w) -> export("points.csv", "text/csv", Geo.csv(points)))
                .setNeutralButton("خروجی GPX", (d, w) -> export("points.gpx", "application/gpx+xml",
                        Geo.gpx(Collections.<Point>emptyList(), points)))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void pointMenu(Point p) {
        String c = Geo.coords(p.lat, p.lon);
        String altitude = Double.isNaN(p.alt) ? "" : "، ارتفاع GPS " + Math.round(p.alt) + " m";
        String[] items = {"نمایش روی نقشه", "کپی مختصات", "ارسال برای دیگران", "حذف"};
        new AlertDialog.Builder(this)
                .setTitle(p.name + "\n‎" + c + "\n±" + Math.round(p.accuracy) + " m" + altitude)
                .setItems(items, (d, i) -> {
                    if (i == 0) {
                        follow = false;
                        map.center(p.lat, p.lon, true);
                    } else if (i == 1) {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("coords", c));
                        toast("کپی شد");
                    } else if (i == 2) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, p.name + "\n" + c);
                        startActivity(Intent.createChooser(send, "ارسال"));
                    } else {
                        confirm("این نقطه حذف شود؟", () -> {
                            Db.get(this).deleteWaypoint(p.id);
                            map.showWaypoints(Db.get(this).waypoints());
                        });
                    }
                })
                .show();
    }

    // ---------- تاریخچه مسیرها ----------

    private void showTracks() {
        List<Db.Track> tracks = Db.get(this).allTracks();
        if (tracks.isEmpty()) {
            toast("هنوز مسیری ثبت نشده است");
            return;
        }
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        String[] items = new String[tracks.size()];
        for (int i = 0; i < items.length; i++) {
            Db.Track t = tracks.get(i);
            items[i] = format.format(new Date(t.start)) + "\n" + Geo.length(t.distance)
                    + (t.finish == 0 ? " (در حال ثبت)" : "  |  " + Geo.duration(t.finish - t.start));
        }
        new AlertDialog.Builder(this)
                .setTitle("مسیرها")
                .setItems(items, (d, i) -> trackMenu(tracks.get(i)))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void trackMenu(Db.Track t) {
        String[] items = {"نمایش روی نقشه", "خروجی GPX", "حذف"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, i) -> {
                    boolean recording = TrackingService.activeTrack(this) != 0;
                    if (i == 0) {
                        if (recording && t.finish != 0) {
                            toast("ابتدا حرکت فعلی را تمام کنید");
                            return;
                        }
                        showTrack(t.id);
                        List<Point> p = Db.get(this).points(t.id, 0);
                        if (!p.isEmpty()) {
                            follow = false;
                            map.center(p.get(0).lat, p.get(0).lon, true);
                        }
                    } else if (i == 1) {
                        export("track-" + t.id + ".gpx", "application/gpx+xml",
                                Geo.gpx(Db.get(this).points(t.id, 0), Collections.<Point>emptyList()));
                    } else if (t.finish == 0) {
                        toast("مسیر در حال ثبت حذف نمی‌شود");
                    } else {
                        confirm("این مسیر حذف شود؟", () -> {
                            Db.get(this).deleteTrack(t.id);
                            if (shownTrack == t.id) {
                                map.clearTrack();
                                shownTrack = 0;
                                origin = null;
                            }
                        });
                    }
                })
                .show();
    }

    // ---------- نقشه آفلاین ----------

    private void loadMap() {
        File file = MapStore.current(this);
        if (file == null) {
            mapMessage = "نقشه آفلاین ندارید؛ دکمه «نقشه آفلاین» را بزنید";
            return;
        }
        try {
            map.loadMap(file);
            mapMessage = null;
        } catch (RuntimeException e) {
            mapMessage = "فایل نقشه خراب است؛ دوباره دانلود کنید";
        }
    }

    private void showMapMenu() {
        if (MapStore.downloading(this)) {
            confirm("دانلود جاری لغو شود؟", () -> {
                MapStore.cancel(this);
                mapMessage = null;
            });
            return;
        }
        String[] items = {"دانلود نقشه ایران (حدود ۳۰۰ مگابایت)", "دانلود از نشانی دلخواه (کشور دیگر)",
                "انتخاب فایل ‎.map از گوشی"};
        new AlertDialog.Builder(this)
                .setTitle("نقشه آفلاین (OpenStreetMap)")
                .setItems(items, (d, i) -> {
                    if (i == 0) {
                        download(MapStore.IRAN_URL);
                    } else if (i == 1) {
                        EditText url = new EditText(this);
                        url.setText("https://download.mapsforge.org/maps/v5/");
                        new AlertDialog.Builder(this)
                                .setTitle("نشانی کامل فایل ‎.map")
                                .setView(url)
                                .setPositiveButton("دانلود", (d2, w) -> download(url.getText().toString().trim()))
                                .setNegativeButton("انصراف", null)
                                .show();
                    } else {
                        open(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT);
                    }
                })
                .show();
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
        } else if (code == REQ_IMPORT) {
            toast("در حال کپی نقشه…");
            new Thread(() -> {
                boolean ok;
                try {
                    MapStore.importFile(getApplicationContext(), uri);
                    ok = true;
                } catch (IOException | RuntimeException e) {
                    ok = false;
                }
                boolean success = ok;
                handler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (success) {
                        loadMap();
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
