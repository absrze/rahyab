package ir.rahyab.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/** ثبت مسیر حتی وقتی صفحه خاموش است یا برنامه در پس‌زمینه است. */
public class TrackingService extends Service implements LocationListener {
    private static final String CHANNEL = "tracking";
    static volatile boolean running;

    private LocationManager manager;
    private PowerManager.WakeLock wakeLock;
    private TrackFilter filter;
    private long trackId;
    private double distance;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("rahyab", MODE_PRIVATE);
    }

    /** شناسه مسیر در حال ثبت؛ صفر یعنی هیچ. */
    static long activeTrack(Context c) {
        return prefs(c).getLong("active", 0);
    }

    static Point toPoint(Location l) {
        return new Point(l.getLatitude(), l.getLongitude(),
                l.hasAltitude() ? l.getAltitude() : Double.NaN,
                l.hasAccuracy() ? l.getAccuracy() : 999f, l.getTime());
    }

    static void finish(Context c) {
        long id = activeTrack(c);
        if (id != 0) {
            Db.get(c).finishTrack(id, System.currentTimeMillis());
            prefs(c).edit().remove("active").apply();
        }
    }

    @SuppressLint({"MissingPermission", "WakelockTimeout"})
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            return START_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, notification());
            }
            Db db = Db.get(this);
            trackId = activeTrack(this);
            Db.Track track = trackId == 0 ? null : db.track(trackId);
            if (track == null) {
                trackId = db.newTrack(System.currentTimeMillis());
                prefs(this).edit().putLong("active", trackId).apply();
                distance = 0;
                filter = new TrackFilter(null);
                // خط دقیقاً از جایی شروع شود که کاربر دکمه شروع را زده است
                if (intent != null && intent.hasExtra("lat")) {
                    Point first = new Point(intent.getDoubleExtra("lat", 0), intent.getDoubleExtra("lon", 0),
                            intent.getDoubleExtra("alt", Double.NaN), intent.getFloatExtra("acc", 999f),
                            intent.getLongExtra("time", System.currentTimeMillis()));
                    if (filter.offer(first) >= 0) {
                        db.addPoint(trackId, first, 0);
                    }
                }
            } else {
                // ادامه مسیر نیمه‌کاره پس از بسته‌شدن ناگهانی
                distance = track.distance;
                List<Point> old = db.points(trackId, 0);
                filter = new TrackFilter(old.isEmpty() ? null : old.get(old.size() - 1));
            }
            manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rahyab:tracking");
            wakeLock.acquire();
            running = true;
        } catch (RuntimeException e) {
            // مجوز موقعیت داده نشده یا سیستم اجازه شروع سرویس را نداده است
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "ثبت مسیر", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("رهیاب در حال ثبت مسیر است")
                .setContentText("برای دیدن نقشه لمس کنید")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!running || activeTrack(this) != trackId) {
            return;
        }
        Point p = toPoint(location);
        double step = filter.offer(p);
        if (step >= 0) {
            distance += step;
            Db.get(this).addPoint(trackId, p, distance);
        }
    }

    // این سه متد برای اندرویدهای قدیمی‌تر لازم است
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    public void onDestroy() {
        running = false;
        if (manager != null) {
            manager.removeUpdates(this);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
