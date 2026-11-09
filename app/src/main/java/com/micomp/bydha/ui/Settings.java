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
}
