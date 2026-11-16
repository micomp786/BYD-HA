package com.micomp.bydha.ui;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Every 15 minutes, starts the background service if it is switched on but not running. The
 * head unit does not always send BOOT_COMPLETED when the car wakes, so this is the fallback.
 */
public final class KeepAliveJob extends JobService {
    private static final String TAG = "BydHaKeepAlive";
    private static final int JOB_ID = 4711;
    private static final int BOOT_JOB_ID = 4712;

    /** One-off start with retries for about five minutes, for after a boot or update. */
    static void startSoon(Context c, String reason) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putString("reason", reason);
        js.schedule(new JobInfo.Builder(BOOT_JOB_ID, new ComponentName(c, KeepAliveJob.class))
                .setMinimumLatency(15_000)
                .setOverrideDeadline(30_000)
                .setExtras(extras)
                .build());
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, KeepAliveJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Settings.enabled(this)) return false;
        final Context app = getApplicationContext();
        new Thread(() -> {
            String reason = params.getExtras().getString("reason", "keep-alive check");
            boolean boot = params.getJobId() == BOOT_JOB_ID;
            try {
                // After a boot adbd and wireless ADB can take a few minutes to come up.
                for (int attempt = 1; attempt <= (boot ? 15 : 1); attempt++) {
                    try {
                        String r = DaemonControl.ensureRunning(app, Settings.load(app), reason);
                        Log.i(TAG, r);
                        if (boot || !r.startsWith("Already")) Settings.note(app, reason + ": " + r);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, reason + " attempt " + attempt + " failed: " + e.getMessage());
                        Settings.note(app, reason + ", attempt " + attempt + " failed: " + e.getMessage());
                    }
                    Thread.sleep(20_000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                jobFinished(params, false);
            }
        }, "bydha-keepalive").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
