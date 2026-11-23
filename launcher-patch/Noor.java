package com.gathin.tvhome;

import android.icu.util.IslamicCalendar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * The line under the clock: Hijri date (Umm al-Qura), the weather now, and the time left to the next
 * prayer. Weather and prayer times come from Open-Meteo and Aladhan in the background; until they arrive the line
 * shows the Hijri date alone. Read once a minute with the clock.
 */
final class Noor {
    static final double LAT = @LAT@, LON = @LON@;
    static final int METHOD = @METHOD@; // Aladhan calculation method

    private static final String[] MONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final String[] PRAYERS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] PRAYERS_AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    // Cards whose tile shows the newest episode's YouTube thumbnail: "package=playlist,package=playlist" (sources.json "latest").
    private static final String LIVE_CARDS = "@LIVE_CARDS@";
    private static final Map<String, Bitmap> liveBanners = new HashMap<>();
    private static final Map<String, String> liveIds = new HashMap<>();
    private static volatile long liveAt;
    private static volatile boolean liveChanged, liveFetching;

    private static volatile String dua; // a weather supplication for the line above the clock, or null
    private static volatile String weather = "";
    private static volatile long weatherAt;
    private static volatile int[] prayerMinutes; // minutes after midnight, Fajr..Isha
    private static volatile int prayerDay = -1;
    private static volatile boolean fetching;
    private static volatile String hijri; // "19 جمادى الأولى 1448" from Aladhan, for the local calendar
    private static volatile int hijriDayAladhan = -1;

    private Noor() {
    }

    static int hijriDay() {
        if (hijriDayAladhan > 0 && prayerDay == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) return hijriDayAladhan;
        IslamicCalendar h = new IslamicCalendar();
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        return h.get(IslamicCalendar.DAY_OF_MONTH);
    }

    /** The weather supplication when it rains, thunders or blows hard; else "بكرة تبدأ الأيام البيض" on the 12th,
     *  "الأيام البيض 🤍 اليوم 13" on the 13th to 15th,
     *  "جمعة مباركة 🌿" on Friday, otherwise صباح الخير / مساء الخير in place of the English greeting. */
    static String greeting() {
        String test = testProp("debug.nafees.weather");
        if (!test.isEmpty()) { // preview: adb shell setprop debug.nafees.weather 61 (rain), 95 (thunder), wind
            try {
                return test.equals("wind") ? dua(0, 60) : dua(Integer.parseInt(test), 0);
            } catch (Exception ignored) {
            }
        }
        String hour = istijaba();
        if (hour != null) return hour;
        String w = dua;
        if (w != null) return w;
        int d = hijriDay();
        String testDay = testProp("debug.nafees.hijri"); // preview: adb shell setprop debug.nafees.hijri 12
        if (!testDay.isEmpty()) try { d = Integer.parseInt(testDay); } catch (Exception ignored) { }
        if (d == 12) return "بكرة تبدأ الأيام البيض 🤍 13 و14 و15";
        if (d >= 13 && d <= 15) return "الأيام البيض 🤍 اليوم " + d;
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) return "جمعة مباركة 🌿";
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 ? "صباح الخير ☀️" : "مساء الخير 🌙";
    }

    static boolean whiteDay() {
        int d = hijriDay();
        return d >= 13 && d <= 15;
    }

    static String line() {
        refreshIfStale();
        StringBuilder b = new StringBuilder();
        if (hijri != null && prayerDay == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
            b.append(hijri);
        } else {
            IslamicCalendar h = new IslamicCalendar();
            h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            b.append(h.get(IslamicCalendar.DAY_OF_MONTH)).append(' ')
                    .append(MONTHS[h.get(IslamicCalendar.MONTH)]).append(' ')
                    .append(h.get(IslamicCalendar.EXTENDED_YEAR));
        }
        if (!weather.isEmpty()) b.append("  ·  ").append(weather);
        String next = nextPrayer();
        if (next != null) b.append('\n').append(next);
        return b.toString();
    }

    /** "🕌 العصر بعد 3:51", or null before the times arrive. */
    private static String nextPrayer() {
        int[] p = prayerMinutes;
        if (p == null) return null;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        for (int i = 0; i < p.length; i++) {
            if (p[i] > now) return "🕌 " + PRAYERS_AR[i] + " بعد " + hm(p[i] - now);
        }
        return "🕌 الفجر بعد " + hm(24 * 60 - now + p[0]); // after Isha: tomorrow's Fajr, close enough
    }

    private static String hm(int minutes) {
        return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60);
    }

    private static void refreshIfStale() {
        int today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        boolean needWeather = System.currentTimeMillis() - weatherAt > 30 * 60_000L;
        boolean needPrayer = prayerDay != today;
        if ((!needWeather && !needPrayer) || fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                if (needPrayer) loadPrayers(today);
                if (needWeather) loadWeather();
            } finally {
                fetching = false;
            }
        }, "noor").start();
    }

    private static void loadPrayers(int today) {
        try {
            Calendar c = Calendar.getInstance();
            String date = String.format(Locale.US, "%02d-%02d-%d", c.get(Calendar.DAY_OF_MONTH),
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
            JSONObject data = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LAT
                    + "&longitude=" + LON + "&method=" + METHOD)).getJSONObject("data");
            JSONObject t = data.getJSONObject("timings");
            JSONObject h = data.getJSONObject("date").getJSONObject("hijri");
            hijriDayAladhan = Integer.parseInt(h.getString("day"));
            hijri = hijriDayAladhan + " " + MONTHS[h.getJSONObject("month").getInt("number") - 1] + " " + h.getString("year");
            int[] m = new int[PRAYERS.length];
            for (int i = 0; i < PRAYERS.length; i++) {
                String[] hhmm = t.getString(PRAYERS[i]).substring(0, 5).split(":");
                m[i] = Integer.parseInt(hhmm[0]) * 60 + Integer.parseInt(hhmm[1]);
            }
            prayerMinutes = m;
            prayerDay = today;
        } catch (Exception ignored) {
            // Offline: keep yesterday's times until the next minute tries again.
        }
    }

    private static void loadWeather() {
        try {
            JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                    + "&longitude=" + LON + "&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m&timezone=auto"))
                    .getJSONObject("current");
            long t = Math.round(cur.getDouble("temperature_2m"));
            int code = cur.getInt("weather_code");
            weather = icon(code, cur.optInt("is_day", 1) == 1) + " " + t + "°";
            dua = dua(code, Math.max(cur.optDouble("wind_speed_10m", 0), cur.optDouble("wind_gusts_10m", 0) * 0.7));
            weatherAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    /**
     * The sunnah supplication for the weather now (WMO code, wind in km/h), or null on a calm dry day.
     * Thunder: Al-Muwatta (Abdullah ibn al-Zubayr). Rain: Al-Bukhari 1032. Wind: Abu Dawud 5097.
     */
    static String dua(int code, double windKmh) {
        if (code >= 95) return "⛈️ سبحان الذي يسبّح الرعد بحمده والملائكة من خيفته";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️ اللهم صيّبًا نافعًا";
        if (windKmh >= 40) return "🌬️ اللهم إني أسألك خيرها، وأعوذ بك من شرها";
        return null;
    }

    // On Friday these cards move to the front of the dock (sources.json "friday_first"), comma separated.
    private static final String FRIDAY_FIRST = "@FRIDAY_FIRST@";
    private static volatile int dockFriday = -1; // 1 Friday, 0 not, as the dock was last ordered

    static boolean friday() {
        return Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
    }

    /** dock_apps as configured, with the Friday cards first on Friday. */
    static java.util.List<String> dockOrder(java.util.List<String> dock) {
        boolean fri = friday();
        dockFriday = fri ? 1 : 0;
        if (!fri || FRIDAY_FIRST.isEmpty()) return dock;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String pkg : FRIDAY_FIRST.split(",")) if (dock.contains(pkg)) out.add(pkg);
        for (String pkg : dock) if (!out.contains(pkg)) out.add(pkg);
        return out;
    }

    static final String PRAYER_PKG = "com.majnioui.tv.prayer";
    private static volatile int prayerDrawn = -1;
    private static volatile Bitmap prayerBitmap;

    /** Today plus which prayer is next, so the tile redraws when either changes. */
    private static int prayerKey() {
        int[] p = prayerMinutes;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), next = 0;
        if (p != null) for (int i = p.length - 1; i >= 0; i--) if (p[i] > now) next = i;
        return prayerDay * 10 + next;
    }

    /** The prayer-times tile: today's five times, the next one in gold. Null until the times arrive. */
    static Bitmap prayerTile() {
        refreshIfStale();
        int[] p = prayerMinutes;
        if (p == null) return null;
        int key = prayerKey();
        if (key == prayerDrawn && prayerBitmap != null) return prayerBitmap;
        int next = key % 10, w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF1F7A55, 0xFF062B1D, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(54);
        title.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("أوقات الصلاة", w - 40, 92, title);
        Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
        name.setTextAlign(Paint.Align.CENTER);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
        gold.setColor(0xFFE2BE68);
        float col = (w - 40) / 5f;
        for (int i = 0; i < 5; i++) {
            float cx = w - 20 - col * i - col / 2; // right to left: Fajr first
            boolean on = i == next;
            if (on) cv.drawRoundRect(new RectF(cx - col / 2 + 6, 140, cx + col / 2 - 6, 320), 22, 22, gold);
            name.setColor(on ? 0xFF1B1204 : 0xCCFFFFFF);
            name.setTextSize(32);
            cv.drawText(PRAYERS_AR[i], cx, 200, name);
            name.setColor(on ? 0xFF1B1204 : Color.WHITE);
            name.setTextSize(44);
            int m = p[i], hh = m / 60 % 12 == 0 ? 12 : m / 60 % 12;
            cv.drawText(String.format(Locale.US, "%d:%02d", hh, m % 60), cx, 280, name);
        }
        prayerBitmap = b;
        prayerDrawn = key;
        return b;
    }

    /** "🤲 ساعة استجابة" with the minutes to Maghrib, in Friday's last hour before Maghrib; otherwise null. */
    static String istijaba() {
        int[] p = prayerMinutes;
        if (p == null) return null;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean test = !testProp("debug.nafees.istijaba").isEmpty(); // preview: adb shell setprop debug.nafees.istijaba 1
        if (!test && (!friday() || now < p[3] - 60 || now >= p[3])) return null;
        return "🤲 ساعة استجابة · المغرب بعد " + hm(Math.max(0, p[3] - now));
    }

    /** A debug.nafees.* system property (only adb can set one), to preview a greeting on demand. */
    private static String testProp(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                    .invoke(null, name);
            return v == null ? "" : v.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** The newest episode's thumbnail for a live card, or null (then the app's own banner shows). */
    static Bitmap liveBanner(String pkg) {
        if (PRAYER_PKG.equals(pkg)) return prayerTile();
        if (!LIVE_CARDS.contains(pkg + "=")) return null;
        if (System.currentTimeMillis() - liveAt > 3 * 3600_000L && !liveFetching) {
            liveFetching = true;
            new Thread(Noor::loadLive, "noor-live").start();
        }
        synchronized (liveBanners) {
            return liveBanners.get(pkg);
        }
    }

    /** True once after a new thumbnail arrives, so Home redraws the tiles. */
    static boolean liveChanged() {
        boolean c = liveChanged || (prayerMinutes != null && prayerKey() != prayerDrawn)
                || (dockFriday >= 0 && dockFriday != (friday() ? 1 : 0)); // Friday began or ended: reorder the dock
        liveChanged = false;
        return c;
    }

    private static void loadLive() {
        try {
            for (String pair : LIVE_CARDS.split(",")) {
                String[] kv = pair.split("=");
                if (kv.length != 2) continue;
                String id = newestVideo(kv[1]);
                if (id == null || id.equals(liveIds.get(kv[0]))) continue; // same episode: nothing to redraw
                Bitmap b = null;
                for (String size : new String[]{"maxresdefault", "mqdefault"}) {
                    b = image("https://i.ytimg.com/vi/" + id + "/" + size + ".jpg");
                    if (b != null) break;
                }
                if (b == null) continue;
                synchronized (liveBanners) {
                    liveBanners.put(kv[0], Bitmap.createScaledBitmap(b, 640, 360, true));
                }
                liveIds.put(kv[0], id);
                liveChanged = true;
            }
            liveAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        } finally {
            liveFetching = false;
        }
    }

    /** The most recently published video in a YouTube playlist, from its public RSS feed. */
    static String newestVideo(String list) throws Exception {
        Matcher m = Pattern.compile("<yt:videoId>([\\w-]{11})</yt:videoId>[\\s\\S]*?<published>([^<]+)</published>")
                .matcher(get("https://www.youtube.com/feeds/videos.xml?playlist_id=" + list));
        String best = null, bestAt = "";
        while (m.find()) {
            if (m.group(2).compareTo(bestAt) > 0) {
                bestAt = m.group(2);
                best = m.group(1);
            }
        }
        return best;
    }

    private static Bitmap image(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return c.getResponseCode() == 200 ? BitmapFactory.decodeStream(in) : null;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather code to one symbol. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "🌙";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 95) return "⛈️";
        if (code >= 51) return "🌧️";
        return "🌤️";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
