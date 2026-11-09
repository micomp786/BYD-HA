package com.micomp.bydha.daemon;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import com.micomp.bydha.common.Paths;

import java.lang.reflect.Method;

/**
 * Builds an Android {@link Context} inside a bare {@code app_process} running as the shell user.
 *
 * <p>The BYD SDK device classes check the signature-level {@code android.permission.BYDAUTO_*}
 * permissions on the Context they are given, inside our own process. The wrapper answers those
 * checks with "granted"; the real access decision then happens in BYD's system service, which
 * accepts the shell uid. Approach learned from Overdrive (MIT, see THIRD_PARTY_NOTICES.md).
 */
final class DaemonContext {
    private DaemonContext() {}

    private static final String TAG = "Context";

    static Context create() throws Exception {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) {
            thread = at.getMethod("systemMain").invoke(null);
        }
        Method getSystemContext = at.getMethod("getSystemContext");
        Context system = (Context) getSystemContext.invoke(thread);
        Context pkg;
        try {
            pkg = system.createPackageContext(Paths.PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            Log.w(TAG, "createPackageContext failed, using system context", e);
            pkg = system;
        }
        return new BydPermissionContext(pkg);
    }

    /**
     * Client-side permission checks all pass, as in Overdrive's shell daemons (proven on a
     * Sealion 6 DM-i). The system services still do their own checks against the shell uid.
     */
    static boolean isBydPermission(String p) {
        return true;
    }

    /** Answers every in-process permission check with "granted". */
    static final class BydPermissionContext extends ContextWrapper {
        BydPermissionContext(Context base) { super(base); }

        @Override public Context getApplicationContext() { return this; }

        @Override public int checkPermission(String p, int pid, int uid) {
            return isBydPermission(p) ? PackageManager.PERMISSION_GRANTED : super.checkPermission(p, pid, uid);
        }
        @Override public int checkCallingPermission(String p) {
            return isBydPermission(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingPermission(p);
        }
        @Override public int checkCallingOrSelfPermission(String p) {
            return isBydPermission(p) ? PackageManager.PERMISSION_GRANTED : super.checkCallingOrSelfPermission(p);
        }
        @Override public int checkSelfPermission(String p) {
            return isBydPermission(p) ? PackageManager.PERMISSION_GRANTED : super.checkSelfPermission(p);
        }
        @Override public void enforcePermission(String p, int pid, int uid, String m) {
            if (!isBydPermission(p)) super.enforcePermission(p, pid, uid, m);
        }
        @Override public void enforceCallingPermission(String p, String m) {
            if (!isBydPermission(p)) super.enforceCallingPermission(p, m);
        }
        @Override public void enforceCallingOrSelfPermission(String p, String m) {
            if (!isBydPermission(p)) super.enforceCallingOrSelfPermission(p, m);
        }
    }
}
