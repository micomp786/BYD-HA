package com.micomp.bydha.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts the background service after the head unit boots, if the user had it on. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BydHaBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Settings.enabled(context)) return;
        final Context app = context.getApplicationContext();
        KeepAliveJob.schedule(app);
        final String reason = "boot: " + intent.getAction();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                // adbd and the vehicle services come up a little after BOOT_COMPLETED.
                for (int attempt = 1; attempt <= 15; attempt++) {
                    Thread.sleep(attempt == 1 ? 15_000 : 20_000);
                    try {
                        String r = DaemonControl.ensureRunning(app, Settings.load(app), reason);
                        Log.i(TAG, r);
                        Settings.note(app, reason + ": " + r);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "start attempt " + attempt + " failed: " + e.getMessage());
                        Settings.note(app, reason + ", attempt " + attempt + " failed: " + e.getMessage());
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }, "bydha-boot").start();
    }
}
