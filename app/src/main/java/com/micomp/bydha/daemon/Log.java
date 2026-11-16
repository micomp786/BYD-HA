package com.micomp.bydha.daemon;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** stdout logger. The watchdog script appends stdout to {@code daemon.log}. */
final class Log {
    private Log() {}

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    static synchronized void i(String tag, String msg) {
        System.out.println(FMT.format(new Date()) + " " + tag + ": " + msg);
        System.out.flush();
    }

    static void w(String tag, String msg, Throwable t) {
        i(tag, msg + (t == null ? "" : " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"));
    }
}
