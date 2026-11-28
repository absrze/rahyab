package ir.rahyab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** حالت ماشین: سرعت‌سنج، هشدار دوربین و سرعت‌گیر، و گزارش کاربر. */
final class DriveAssistant {
    private static final long RETRY_MS = 5 * 60_000;
    private static final double RELOAD_STEP = 500;      // متر

    private final Activity activity;
    private final MapScreen map;
    private final AlertEngine engine = new AlertEngine();
    private List<Hazard> near = new ArrayList<>();
    private Location loadedAt;
    private boolean active;
    private boolean downloading;
    private long lastAttempt = -RETRY_MS;
    private float bearing = Float.NaN;
    private TextToSpeech speech;
    private boolean persianVoice;
    private ToneGenerator tone;

    DriveAssistant(Activity activity, MapScreen map) {
        this.activity = activity;
        this.map = map;
        map.setHazardListener(this::showHazard);
    }

    void setActive(boolean on, Location last) {
        active = on;
        loadedAt = null;
        view(R.id.speedometer).setVisibility(on ? View.VISIBLE : View.GONE);
        view(R.id.alert).setVisibility(View.GONE);
        if (!on) {
            near = new ArrayList<>();
            map.showHazards(near);
            return;
        }
        speed(0);
        if (speech == null) {
            speech = new TextToSpeech(activity.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && speech != null) {
                    persianVoice = speech.setLanguage(new Locale("fa", "IR")) >= TextToSpeech.LANG_AVAILABLE;
                }
            });
        }
        if (last != null) {
            onLocation(last, false);
        }
    }

    /** با هر موقعیت GPS. live یعنی موقعیت تازه است. */
    void onLocation(Location l, boolean live) {
        if (!active) {
            return;
        }
        double metersPerSecond = live && l.hasSpeed() ? l.getSpeed() : 0;
        // در سرعت کم جهت GPS نامعتبر است
        if (l.hasBearing() && metersPerSecond > 2) {
            bearing = l.getBearing();
            map.setHeading(bearing);
        }
        if (loadedAt == null || loadedAt.distanceTo(l) > RELOAD_STEP) {
            reload(l);
            // اگر اطراف داده‌ای از OSM نیست، یک بار تلاش برای دریافت
            long now = SystemClock.elapsedRealtime();
            if (live && now - lastAttempt >= RETRY_MS && needsDownload(l)) {
                download(l, false);
            }
        }
        AlertEngine.Alert alert = Float.isNaN(bearing) ? null
                : engine.update(near, l.getLatitude(), l.getLongitude(), bearing, metersPerSecond);
        speed(Math.round(metersPerSecond * 3.6f));
        TextView banner = (TextView) view(R.id.alert);
        if (alert == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        boolean over = alert.hazard.limit > 0 && metersPerSecond * 3.6 > alert.hazard.limit;
        String text = (alert.hazard.type == Hazard.CAMERA ? "📷 " : "⚠ ") + alert.hazard.title() + "  "
                + Math.round(alert.distance / 10) * 10 + " متر جلوتر";
        if (alert.hazard.limit > 0) {
            text += "\nسرعت مجاز " + alert.hazard.limit + (over ? " — سرعت را کم کنید!" : "");
        }
        banner.setText(text);
        banner.setBackgroundResource(over || alert.hazard.type == Hazard.CAMERA
                ? R.drawable.bg_alert_red : R.drawable.bg_alert_orange);
        banner.setVisibility(View.VISIBLE);
        if (alert.first) {
            announce(alert.hazard.title() + " جلوتر است");
        }
    }

    /** موقعیت GPS قطع شده است؛ عدد سرعت و هشدار قدیمی نماند. */
    void onSignalLost() {
        if (active) {
            speed(-1);
            view(R.id.alert).setVisibility(View.GONE);
        }
    }

    private void speed(int kmh) {
        ((TextView) view(R.id.speedometer)).setText((kmh < 0 ? "—" : String.valueOf(kmh)) + "\nkm/h");
    }

    private void announce(String words) {
        if (persianVoice) {
            speech.speak(words, TextToSpeech.QUEUE_FLUSH, null, "hazard");
            return;
        }
        // گوشی صدای فارسی ندارد؛ بوق هشدار
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            }
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
        } catch (RuntimeException e) {
            tone = null;
        }
    }

    private void reload(Location l) {
        loadedAt = l;
        near = Db.get(activity).hazardsNear(l.getLatitude(), l.getLongitude(), 3000);
        // نزدیک‌ترها اول، چون فقط تعداد محدودی روی نقشه رسم می‌شود
        near.sort((a, b) -> Double.compare(
                Geo.distance(l.getLatitude(), l.getLongitude(), a.lat, a.lon),
                Geo.distance(l.getLatitude(), l.getLongitude(), b.lat, b.lon)));
        map.showHazards(near);
    }

    private boolean needsDownload(Location l) {
        String saved = TrackingService.prefs(activity).getString("hazardArea", null);
        if (saved == null) {
            return true;
        }
        String[] p = saved.split(",");
        boolean far = Geo.distance(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                l.getLatitude(), l.getLongitude()) > HazardStore.RADIUS / 2.0;
        boolean old = System.currentTimeMillis() - Long.parseLong(p[2]) > 7L * 24 * 3600 * 1000;
        return far || old;
    }

    void download(Location l, boolean byUser) {
        if (downloading) {
            return;
        }
        downloading = true;
        lastAttempt = SystemClock.elapsedRealtime();
        double lat = l.getLatitude();
        double lon = l.getLongitude();
        if (byUser) {
            toast("در حال دریافت دوربین‌ها و سرعت‌گیرهای اطراف…");
        }
        android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            String message;
            try {
                List<Hazard> list = HazardStore.fetch(lat, lon);
                Db.get(app).replaceOsmHazards(lat, lon, list);
                TrackingService.prefs(app).edit()
                        .putString("hazardArea", lat + "," + lon + "," + System.currentTimeMillis()).apply();
                int cameras = 0;
                for (Hazard h : list) {
                    cameras += h.type == Hazard.CAMERA ? 1 : 0;
                }
                message = list.isEmpty()
                        ? "برای این منطقه دوربین یا سرعت‌گیری در OpenStreetMap ثبت نشده است"
                        : "تا شعاع ۲۰ کیلومتر: " + cameras + " دوربین و " + (list.size() - cameras) + " سرعت‌گیر";
            } catch (IOException | JSONException | RuntimeException e) {
                message = byUser ? "دریافت ناموفق بود؛ اینترنت را بررسی کنید" : null;
            }
            String result = message;
            activity.runOnUiThread(() -> {
                downloading = false;
                if (activity.isDestroyed()) {
                    return;
                }
                loadedAt = null;
                if (active && result != null) {
                    toast(result);
                }
            });
        }).start();
    }

    /** ثبت دوربین یا سرعت‌گیر در مکان فعلی. */
    void report(Location l) {
        // مختصات لحظه لمس دکمه، پیش از باز شدن منو
        double lat = l.getLatitude();
        double lon = l.getLongitude();
        new Sheet(activity, "گزارش در همین نقطه", "فقط وقتی خودرو متوقف است یا توسط سرنشین")
                .row("📷", "دوربین سرعت", null, () -> add(Hazard.CAMERA, lat, lon))
                .row("⚠", "سرعت‌گیر / دست‌انداز", null, () -> add(Hazard.BUMP, lat, lon))
                .row("⬇", "به‌روزرسانی داده‌های اطراف", "از OpenStreetMap", () -> download(l, true))
                .show();
    }

    private void add(int type, double lat, double lon) {
        Db.get(activity).addHazard(0, type, lat, lon, 0);
        loadedAt = null;
        near = Db.get(activity).hazardsNear(lat, lon, 3000);
        map.showHazards(near);
        toast("ثبت شد");
    }

    private void showHazard(Hazard h) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(h.title())
                .setMessage((h.mine ? "گزارش شما" : "از OpenStreetMap")
                        + (h.limit > 0 ? "\nسرعت مجاز: " + h.limit : "")
                        + "\n\u200E" + Geo.coords(h.lat, h.lon))
                .setPositiveButton("بستن", null);
        if (h.mine) {
            b.setNegativeButton("حذف", (d, w) -> {
                Db.get(activity).deleteHazard(h.id);
                near = Db.get(activity).hazardsNear(h.lat, h.lon, 3000);
                loadedAt = null;
                map.showHazards(near);
            });
        }
        b.show();
    }

    private View view(int id) {
        return activity.findViewById(id);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    void destroy() {
        if (speech != null) {
            speech.shutdown();
            speech = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }
}
