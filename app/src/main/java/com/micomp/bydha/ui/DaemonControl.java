package com.micomp.bydha.ui;

import android.content.Context;

import com.micomp.bydha.common.Config;
import com.micomp.bydha.common.Paths;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Talks to the head unit's own ADB daemon over 127.0.0.1:5555 to install and run the
 * background process as the shell user. All methods block; call them off the UI thread.
 */
final class DaemonControl {
    private DaemonControl() {}

    private static final int ADB_PORT = 5555;
    /** Long enough for the user to tap "Allow" on the first-time ADB prompt. */
    private static final int SOCKET_TIMEOUT_MS = 90_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    static Dadb connect(Context c) throws Exception {
        File priv = new File(c.getFilesDir(), "adbkey");
        File pub = new File(c.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
        AdbKeyPair kp = AdbKeyPair.read(priv, pub);
        try {
            return Dadb.create("127.0.0.1", ADB_PORT, kp, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
        } catch (Exception e) {
            throw new Exception("Could not reach ADB on port " + ADB_PORT
                    + ". Turn on Wireless ADB in the head unit's developer settings, then try again. ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }

    private static String sh(Dadb adb, String cmd) throws Exception {
        AdbShellResponse r = adb.shell(cmd);
        return (r.getOutput() + r.getErrorOutput()).trim();
    }

    private static void push(Context c, Dadb adb, String content, String remote, int mode) throws Exception {
        File tmp = new File(c.getCacheDir(), "push.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        try {
            adb.push(tmp, remote, mode, System.currentTimeMillis());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Writes the config and (re)starts the watchdog + daemon. Returns a short report. */
    static String start(Context c, Config cfg) throws Exception {
        try (Dadb adb = connect(c)) {
            sh(adb, "mkdir -p " + Paths.TS_DIR + " && rm -f " + Paths.DISABLED);
            push(c, adb, cfg.toJson().toString(1), Paths.CONFIG, 0600);
            push(c, adb, watchdogScript(), Paths.WATCHDOG, 0700);
            // Android 12+ kills "phantom" child processes of apps; ours belong to adbd, but be safe.
            sh(adb, "/system/bin/device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1; true");
            String running = sh(adb, "P=$(cat " + Paths.WATCHDOG_PID + " 2>/dev/null); "
                    + "[ -n \"$P\" ] && grep -q watchdog /proc/$P/cmdline 2>/dev/null && echo yes || echo no");
            if (running.endsWith("yes")) {
                // The daemon notices the new config file and restarts itself.
                return "Settings sent. The background service is restarting with them.";
            }
            sh(adb, "cd " + Paths.BASE + "; "
                    + "if command -v setsid >/dev/null 2>&1; then "
                    + "setsid nohup sh " + Paths.WATCHDOG + " >/dev/null 2>&1 </dev/null & "
                    + "else nohup sh " + Paths.WATCHDOG + " >/dev/null 2>&1 </dev/null & fi");
            return "Background service started.";
        }
    }

    static String stop(Context c) throws Exception {
        try (Dadb adb = connect(c)) {
            sh(adb, "touch " + Paths.DISABLED + "; "
                    + "P=$(cat " + Paths.WATCHDOG_PID + " 2>/dev/null); [ -n \"$P\" ] && kill $P; "
                    + "pkill -f " + Paths.PROCESS_NAME + "; "
                    + "pkill -f -- '--statedir=" + Paths.TS_DIR + "'; true");
            return "Background service stopped.";
        }
    }

    static String status(Context c) throws Exception {
        try (Dadb adb = connect(c)) {
            String alive = sh(adb, "pidof " + Paths.PROCESS_NAME + " >/dev/null 2>&1 && echo running || echo 'not running'");
            String s = sh(adb, "cat " + Paths.STATUS + " 2>/dev/null");
            return "Service: " + alive + "\n\n" + (s.isEmpty() ? "(no status yet)" : s);
        }
    }

    static String log(Context c) throws Exception {
        try (Dadb adb = connect(c)) {
            return sh(adb, "tail -n 150 " + Paths.LOG + " 2>/dev/null; echo; echo '--- tailscaled ---'; "
                    + "tail -n 30 " + Paths.TS_DIR + "/tailscaled.log 2>/dev/null");
        }
    }

    static String dump(Context c) throws Exception {
        try (Dadb adb = connect(c)) {
            String d = sh(adb, "cat " + Paths.BASE + "/dump.json 2>/dev/null");
            return d.isEmpty() ? "No dump yet. It is written about 5 seconds after the service starts." : d;
        }
    }

    static String watchdogScript() {
        String b = Paths.BASE;
        return "#!/system/bin/sh\n"
                + "# Keeps the BYD-HA background process running. Started by the app over ADB.\n"
                + "LOG=" + Paths.LOG + "\n"
                + "echo $$ > " + Paths.WATCHDOG_PID + "\n"
                + "while true; do\n"
                + "  [ -f " + Paths.DISABLED + " ] && exit 0\n"
                + "  APK=$(pm path " + Paths.PACKAGE + " 2>/dev/null | grep base.apk | head -n 1 | sed 's/^package://')\n"
                + "  if [ -z \"$APK\" ]; then echo \"$(date) app not installed, watchdog exiting\" >> $LOG; exit 0; fi\n"
                + "  if [ -f $LOG ] && [ $(wc -c < $LOG) -gt 2000000 ]; then tail -c 500000 $LOG > $LOG.tmp && mv $LOG.tmp $LOG; fi\n"
                + "  echo \"$(date) watchdog: starting daemon from $APK\" >> $LOG\n"
                + "  CLASSPATH=\"$APK\" app_process /system/bin --nice-name=" + Paths.PROCESS_NAME + " "
                + Paths.DAEMON_CLASS + " >> $LOG 2>&1\n"
                + "  echo \"$(date) watchdog: daemon exited with $?\" >> $LOG\n"
                + "  sleep 5\n"
                + "done\n";
    }
}
