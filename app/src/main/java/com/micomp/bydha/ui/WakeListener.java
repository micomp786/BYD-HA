package com.micomp.bydha.ui;

import android.service.notification.NotificationListenerService;

/**
 * The head unit keeps third-party apps in a stopped state after it powers up, so they get no
 * boot broadcast and no scheduled jobs until opened. Android binds enabled notification listeners
 * itself after boot, which brings this app up; we use that only as a wake-up signal and never
 * read any notifications. The service turns this listener on through ADB when it starts.
 */
public final class WakeListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        if (!Settings.enabled(this)) return;
        Settings.note(this, "woken by the system (notification listener)");
        KeepAliveJob.schedule(getApplicationContext());
        KeepAliveJob.startSoon(getApplicationContext(), "wake: listener connected");
    }
}
