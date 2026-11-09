package com.micomp.bydha.daemon;

import android.content.Context;

import com.micomp.bydha.common.Config;
import com.micomp.bydha.common.Paths;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs Tailscale's own {@code tailscaled} in userspace-networking mode as the shell user, with a
 * local SOCKS5 proxy that the MQTT connection uses. No Android VPN permission is needed.
 *
 * <p>The APK ships a combined tailscale/tailscaled binary (built in CI from upstream source,
 * BSD-3-Clause) as {@code libtailscale.so}, so Android extracts it as an executable.
 */
final class TailscaleRunner {
    private static final String TAG = "Tailscale";

    private final Config cfg;
    private final String binary;
    private Process daemon;
    private volatile String state = "off";

    TailscaleRunner(Context ctx, Config cfg) {
        this.cfg = cfg;
        this.binary = ctx.getApplicationInfo().nativeLibraryDir + "/libtailscale.so";
    }

    String state() {
        return state;
    }

    /** Starts tailscaled and logs in. Safe to call again to restart a dead daemon. */
    synchronized void start() {
        if (!new File(binary).canExecute()) {
            state = "error: tailscale binary missing (" + binary + ")";
            Log.i(TAG, state);
            return;
        }
        try {
            new File(Paths.TS_DIR).mkdirs();
            run(5, "sh", "-c", "ln -sf '" + binary + "' '" + Paths.TS_CLI_LINK + "'");
            // A tailscaled left over from a previous daemon run holds the socket and port.
            run(5, "sh", "-c", "pkill -f -- '--statedir=" + Paths.TS_DIR + "' ; true");
            new File(Paths.TS_SOCKET).delete();

            state = "starting";
            ProcessBuilder pb = new ProcessBuilder(binary,
                    "--tun=userspace-networking",
                    "--socks5-server=127.0.0.1:" + Paths.SOCKS_PORT,
                    "--statedir=" + Paths.TS_DIR,
                    "--socket=" + Paths.TS_SOCKET,
                    "--port=0",
                    "--no-logs-no-support");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(Paths.TS_DIR, "tailscaled.log")));
            pb.environment().put("HOME", Paths.TS_DIR);
            daemon = pb.start();
            Log.i(TAG, "tailscaled started");

            for (int i = 0; i < 40 && !new File(Paths.TS_SOCKET).exists(); i++) {
                Thread.sleep(500);
            }

            List<String> up = new ArrayList<>(Arrays.asList(Paths.TS_CLI_LINK,
                    "--socket=" + Paths.TS_SOCKET, "up", "--reset",
                    "--hostname=" + cfg.tailscaleHostname,
                    "--accept-dns=false", "--accept-routes=false", "--timeout=90s"));
            if (!cfg.tailscaleAuthKey.isEmpty()) up.add("--auth-key=" + cfg.tailscaleAuthKey);
            String out = run(120, up.toArray(new String[0]));
            Log.i(TAG, "tailscale up: " + out.replace(cfg.tailscaleAuthKey.isEmpty() ? "\u0000" : cfg.tailscaleAuthKey, "***"));
            refreshState();
        } catch (Exception e) {
            state = "error: " + Byd.describe(e);
            Log.w(TAG, "start failed", e);
        }
    }

    /** Restarts tailscaled if it died, and updates {@link #state()}. */
    synchronized void check() {
        if (daemon != null && !daemon.isAlive()) {
            Log.i(TAG, "tailscaled exited (" + daemon.exitValue() + "), restarting");
            start();
            return;
        }
        refreshState();
    }

    private void refreshState() {
        try {
            String json = run(15, Paths.TS_CLI_LINK, "--socket=" + Paths.TS_SOCKET, "status", "--json");
            JSONObject o = new JSONObject(json.substring(Math.max(0, json.indexOf('{'))));
            String backend = o.optString("BackendState", "unknown");
            JSONObject self = o.optJSONObject("Self");
            String ip = "";
            if (self != null && self.optJSONArray("TailscaleIPs") != null
                    && self.optJSONArray("TailscaleIPs").length() > 0) {
                ip = " " + self.optJSONArray("TailscaleIPs").optString(0);
            }
            if ("NeedsLogin".equals(backend)) {
                state = "needs login (add an auth key)";
            } else {
                state = backend.toLowerCase(java.util.Locale.ROOT) + ip;
            }
        } catch (Exception e) {
            state = "error: " + Byd.describe(e);
        }
    }

    void stop() {
        Process p = daemon;
        if (p != null) p.destroy();
    }

    /** Runs a command, returning combined output. The CLI symlink name makes the binary act as the CLI. */
    private static String run(int timeoutSec, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("HOME", Paths.TS_DIR);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            } catch (Exception ignored) {
            }
        });
        reader.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy();
            throw new IllegalStateException("timed out: " + cmd[cmd.length > 2 ? 2 : 0]);
        }
        reader.join(2000);
        return sb.toString().trim();
    }
}
