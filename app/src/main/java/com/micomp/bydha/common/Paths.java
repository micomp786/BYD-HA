package com.micomp.bydha.common;

/** Files shared between the app (which writes them over ADB) and the shell-user daemon. */
public final class Paths {
    private Paths() {}

    public static final String PACKAGE = "com.micomp.bydha";
    public static final String DAEMON_CLASS = "com.micomp.bydha.daemon.DaemonMain";
    public static final String PROCESS_NAME = "bydha_daemon";

    /** Owned by the shell user (uid 2000); the app itself cannot read it, only through ADB. */
    public static final String BASE = "/data/local/tmp/bydha";
    public static final String CONFIG = BASE + "/config.json";
    public static final String STATUS = BASE + "/status.json";
    public static final String LOG = BASE + "/daemon.log";
    public static final String WATCHDOG = BASE + "/watchdog.sh";
    public static final String WATCHDOG_PID = BASE + "/watchdog.pid";
    public static final String DISABLED = BASE + "/disabled";
    public static final String TS_DIR = BASE + "/ts";
    public static final String TS_SOCKET = TS_DIR + "/tailscaled.sock";
    /** Copy of the APK's libtailscale.so; the shell user may not exec inside /data/app. */
    public static final String TS_BINARY = TS_DIR + "/tailscaled";
    public static final String TS_CLI_LINK = TS_DIR + "/tailscale";
    public static final String TS_LOG = TS_DIR + "/tailscaled.log";

    public static final int SOCKS_PORT = 1055;
}
