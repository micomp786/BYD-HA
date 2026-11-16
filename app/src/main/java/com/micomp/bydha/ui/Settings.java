package com.micomp.bydha.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.micomp.bydha.common.Config;

/** App-side copy of the settings. The daemon gets its own copy pushed over ADB. */
final class Settings {
    private Settings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bydha", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        return Config.fromJson(prefs(c).getString("config", ""));
    }

    static void save(Context c, Config cfg) {
        prefs(c).edit().putString("config", cfg.toJson().toString()).apply();
    }

    /** Whether the service should be started again after a reboot. */
    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    /** Keeps the last 30 background start attempts, so the Status screen can show them. */
    static synchronized void note(Context c, String line) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        String old = prefs(c).getString("history", "");
        String all = stamp + " " + line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = all.split("\n");
        if (lines.length > 30) all = String.join("\n", java.util.Arrays.copyOf(lines, 30));
        prefs(c).edit().putString("history", all).commit();
    }

    static String history(Context c) {
        return prefs(c).getString("history", "");
    }
}
