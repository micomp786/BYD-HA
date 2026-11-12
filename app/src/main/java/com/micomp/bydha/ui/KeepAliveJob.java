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
            try {
                Log.i(TAG, DaemonControl.ensureRunning(app, Settings.load(app), "keep-alive check"));
            } catch (Exception e) {
                Log.w(TAG, "keep-alive failed: " + e.getMessage());
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
