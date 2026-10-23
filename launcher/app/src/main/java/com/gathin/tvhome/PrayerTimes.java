package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

final class PrayerTimes {
    static final String ZONE = "SGR01";
    static final String PLACE = "Sepang";
    private static final String API =
            "https://api.waktusolat.app/v2/solat/" + ZONE;

    private static final String PREFS = "prayer_times";
    private static final String KEY_JSON = "json";
    private static final String KEY_FETCHED = "fetched";
    private static final long CACHE_MS = 24L * 60L * 60L * 1000L;

    static final class Today {
        final String hijri;
        final long fajr;
        final long syuruk;
        final long dhuhr;
        final long asr;
        final long maghrib;
        final long isha;

        Today(String hijri, long fajr, long syuruk, long dhuhr,
              long asr, long maghrib, long isha) {
            this.hijri = hijri;
            this.fajr = fajr;
            this.syuruk = syuruk;
            this.dhuhr = dhuhr;
            this.asr = asr;
            this.maghrib = maghrib;
            this.isha = isha;
        }
    }

    private PrayerTimes() {}

    static Today cachedToday(Context ctx) {
        try {
            String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_JSON, null);
            return json == null ? null : parseToday(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    static Today refresh(Context ctx) throws Exception {
        SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String existing = p.getString(KEY_JSON, null);
        long fetched = p.getLong(KEY_FETCHED, 0);
        long now = System.currentTimeMillis();

        if (existing != null && now - fetched < CACHE_MS) {
            Today today = parseToday(existing);
            if (today != null) return today;
        }

        String json = get(API);
        Today today = parseToday(json);
        if (today != null) {
            p.edit()
                    .putString(KEY_JSON, json)
                    .putLong(KEY_FETCHED, now)
                    .apply();
        }
        return today;
    }

    private static Today parseToday(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray prayers = root.getJSONArray("prayers");

        Calendar cal = Calendar.getInstance(
                TimeZone.getTimeZone("Asia/Kuala_Lumpur"),
                Locale.US);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1;
        int year = cal.get(Calendar.YEAR);

        if (root.optInt("year") != year ||
                root.optInt("month_number") != month) {
            return null;
        }

        for (int i = 0; i < prayers.length(); i++) {
            JSONObject p = prayers.getJSONObject(i);
            if (p.getInt("day") == day) {
                return new Today(
                        p.optString("hijri", ""),
                        p.getLong("fajr"),
                        p.getLong("syuruk"),
                        p.getLong("dhuhr"),
                        p.getLong("asr"),
                        p.getLong("maghrib"),
                        p.getLong("isha"));
            }
        }
        return null;
    }

    static String time(long epochSeconds) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        return f.format(new java.util.Date(epochSeconds * 1000L));
    }

    static int nextIndex(Today t) {
        long now = System.currentTimeMillis() / 1000L;
        long[] times = {
                t.fajr, t.syuruk, t.dhuhr,
                t.asr, t.maghrib, t.isha
        };

        for (int i = 0; i < times.length; i++) {
            if (now < times[i]) return i;
        }
        return -1;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c =
                (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");

        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException(
                        "HTTP " + c.getResponseCode());
            }

            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out =
                         new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 256 * 1024) {
                        throw new IllegalStateException(
                                "Prayer response too large");
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(
                        StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}