package com.micomp.bydha.daemon;

import android.content.Context;
import android.os.Looper;

import com.micomp.bydha.common.Config;
import com.micomp.bydha.common.Paths;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point of the background process. Started by the watchdog script as the shell user:
 * {@code CLASSPATH=<apk> app_process /system/bin com.micomp.bydha.daemon.DaemonMain}.
 *
 * <p>Reads the car every {@code pollSeconds}, publishes to MQTT, and writes {@code status.json}
 * for the app's status screen. Exits when {@code config.json} changes; the watchdog then
 * starts it again with the new settings.
 */
public final class DaemonMain {
    private static final String TAG = "Daemon";
    /** Even with nothing changing, re-publish at least this often so HA sees fresh data. */
    private static final long HEARTBEAT_MS = 5 * 60_000L;

    private final Config cfg;
    private final long configStamp;
    private long apkStamp;
    private final Context ctx;
    private final String version;
    private final Byd byd;
    private final VehicleReader reader;
    private final MqttLink mqtt;
    private final TailscaleRunner tailscale;
    private final AtomicBoolean dumpRequested = new AtomicBoolean(true);
    private final long startedAt = System.currentTimeMillis();
    private String lastPayload = "";
    private long lastPublishAt;
    private String lastLocationPayload = "";
    private long lastLocationAt;
    private Map<String, Object> lastState;
    private String lastError = "";

    public static void main(String[] args) {
        Log.i(TAG, "starting, uid=" + android.os.Process.myUid());
        try {
            new DaemonMain().run();
        } catch (Throwable t) {
            Log.w(TAG, "fatal", t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private DaemonMain() throws Exception {
        File cf = new File(Paths.CONFIG);
        configStamp = cf.lastModified();
        cfg = Config.fromJson(cf.exists() ? new String(Files.readAllBytes(cf.toPath()), StandardCharsets.UTF_8) : "");
        ctx = DaemonContext.create();
        String v = "?";
        try {
            v = ctx.getPackageManager().getPackageInfo(Paths.PACKAGE, 0).versionName;
        } catch (Exception ignored) {
        }
        version = v;
        apkStamp = apkUpdateTime();
        byd = new Byd(ctx);
        reader = new VehicleReader(byd, ctx);
        mqtt = new MqttLink(cfg, version, this::onCommand);
        tailscale = cfg.tailscaleEnabled ? new TailscaleRunner(ctx, cfg) : null;
        Log.i(TAG, "version " + version + ", broker " + cfg.brokerHost + ":" + cfg.brokerPort
                + ", tailscale " + (cfg.tailscaleEnabled ? "on" : "off"));
    }

    private void run() {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mqtt.close();
            if (tailscale != null) tailscale.stop();
        }));

        sched.execute(() -> {
            if (tailscale != null) tailscale.start();
            if (cfg.brokerHost.isEmpty()) {
                lastError = "No MQTT broker set. Open the app and fill in the broker address.";
                Log.i(TAG, lastError);
                return;
            }
            mqtt.connectLoop();
        });

        sched.scheduleWithFixedDelay(this::safeTick, 3, cfg.pollSeconds, TimeUnit.SECONDS);
        if (tailscale != null) {
            sched.scheduleWithFixedDelay(tailscale::check, 60, 60, TimeUnit.SECONDS);
        }

        Looper.loop(); // keeps the main thread (and any SDK callbacks posted to it) alive
    }

    private void onCommand(String cmd) {
        Log.i(TAG, "command: " + cmd);
        if ("dump".equalsIgnoreCase(cmd)) dumpRequested.set(true);
        if ("restart".equalsIgnoreCase(cmd)) System.exit(0);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            lastError = Byd.describe(t);
            Log.w(TAG, "tick failed", t);
        }
    }

    private void tick() throws Exception {
        File cf = new File(Paths.CONFIG);
        long apk = apkUpdateTime();
        if (apk != 0 && apkStamp != 0 && apk != apkStamp) {
            // The app was updated: exit so the watchdog starts the new code.
            Log.i(TAG, "app updated, restarting");
            mqtt.close();
            if (tailscale != null) tailscale.stop();
            System.exit(0);
        }
        if (cf.lastModified() != configStamp) {
            Log.i(TAG, "config changed, restarting");
            mqtt.close();
            if (tailscale != null) tailscale.stop();
            System.exit(0);
        }

        Map<String, Object> state = reader.read();
        state.put("tailscale", tailscale == null ? "off" : tailscale.state());
        lastState = state;

        JSONObject json = new JSONObject();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            json.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
        }
        String payload = json.toString();
        long now = System.currentTimeMillis();
        if (mqtt.isConnected() && (!payload.equals(lastPayload) || now - lastPublishAt > HEARTBEAT_MS)) {
            json.put("last_update", iso(now));
            if (mqtt.publish(cfg.topic("state"), json.toString(), true)) {
                lastPayload = payload;
                lastPublishAt = now;
            }
        }

        Map<String, Object> loc = reader.location();
        if (loc != null && mqtt.isConnected()) {
            String lp = new JSONObject(loc).toString();
            if (!lp.equals(lastLocationPayload) || now - lastLocationAt > HEARTBEAT_MS) {
                if (mqtt.publish(cfg.topic("location"), lp, true)) {
                    lastLocationPayload = lp;
                    lastLocationAt = now;
                }
            }
        }

        if (dumpRequested.getAndSet(false)) {
            JSONObject dump = new JSONObject(byd.dumpAll());
            String d = dump.toString(1);
            writeFile(Paths.BASE + "/dump.json", d);
            mqtt.publish(cfg.topic("debug"), dump.toString(), false);
            Log.i(TAG, "debug dump written (" + dump.length() + " values)");
        }

        writeStatus(now, loc);
    }

    private void writeStatus(long now, Map<String, Object> loc) {
        try {
            JSONObject s = new JSONObject();
            s.put("updated", iso(now));
            s.put("started", iso(startedAt));
            s.put("version", version);
            s.put("mqtt", cfg.brokerHost.isEmpty() ? "not configured" : mqtt.state());
            s.put("tailscale", tailscale == null ? "off" : tailscale.state());
            if (tailscale != null) s.put("tailscaleLog", TailscaleRunner.logTail(4));
            s.put("lastPublish", lastPublishAt == 0 ? "never" : iso(lastPublishAt));
            s.put("error", lastError);
            s.put("location", loc == null ? "no fix" : new JSONObject(loc));
            s.put("deviceErrors", new JSONObject(byd.deviceErrors()));
            JSONObject st = new JSONObject();
            if (lastState != null) {
                for (Map.Entry<String, Object> e : lastState.entrySet()) {
                    st.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
                }
            }
            s.put("state", st);
            writeFile(Paths.STATUS, s.toString(1));
        } catch (Exception e) {
            Log.w(TAG, "status write failed", e);
        }
    }

    private long apkUpdateTime() {
        try {
            return ctx.getPackageManager().getPackageInfo(Paths.PACKAGE, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeFile(String path, String content) throws Exception {
        File tmp = new File(path + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(path))) throw new IllegalStateException("rename failed: " + path);
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }
}
