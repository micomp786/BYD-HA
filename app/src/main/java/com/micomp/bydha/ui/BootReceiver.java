package com.micomp.bydha.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the background service after the head unit boots or the app updates, if the user had
 * it on. The retries run in a job, because a broadcast receiver may only run for a few seconds.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Settings.enabled(context)) return;
        Context app = context.getApplicationContext();
        KeepAliveJob.schedule(app);
        KeepAliveJob.startSoon(app, "boot: " + intent.getAction());
    }
}
