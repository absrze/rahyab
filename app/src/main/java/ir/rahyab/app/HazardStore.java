package ir.rahyab.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** دریافت دوربین‌ها و سرعت‌گیرهای ثبت‌شده در OpenStreetMap از سرویس Overpass. */
final class HazardStore {
    static final int RADIUS = 20000;    // متر

    private HazardStore() { }

    /** روی رشته پس‌زمینه صدا زده شود. */
    static List<Hazard> fetch(double lat, double lon) throws IOException, JSONException {
        String around = String.format(Locale.US, "(around:%d,%.5f,%.5f)", RADIUS, lat, lon);
        String query = "[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"]" + around
                + ";node[\"traffic_calming\"~\"^(bump|hump|table|cushion|yes)$\"]" + around + ";);out body;";
        HttpURLConnection h = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter")
                .openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(40000);
        h.setDoOutput(true);
        h.setRequestProperty("User-Agent", "Rahyab/4.0 (Android)");
        try {
            try (OutputStream out = h.getOutputStream()) {
                out.write(("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            if (h.getResponseCode() != 200) {
                throw new IOException("server " + h.getResponseCode());
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    body.write(buffer, 0, n);
                }
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            // پاسخ ناقص (زمان تمام‌شده) نباید داده قبلی را پاک کند
            if (json.has("remark")) {
                throw new IOException("incomplete answer");
            }
            JSONArray elements = json.getJSONArray("elements");
            List<Hazard> list = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject e = elements.getJSONObject(i);
                JSONObject tags = e.optJSONObject("tags");
                if (tags == null) {
                    continue;
                }
                boolean camera = "speed_camera".equals(tags.optString("highway"));
                list.add(new Hazard(e.getLong("id"), camera ? Hazard.CAMERA : Hazard.BUMP,
                        e.getDouble("lat"), e.getDouble("lon"), limit(tags.optString("maxspeed")), false));
            }
            return list;
        } finally {
            h.disconnect();
        }
    }

    private static int limit(String value) {
        return value.matches("\\d{1,3}") ? Integer.parseInt(value) : 0;
    }
}
