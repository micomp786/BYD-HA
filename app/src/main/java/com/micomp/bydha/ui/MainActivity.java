package com.micomp.bydha.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.micomp.bydha.common.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single settings + status screen. Built in code to keep the app dependency-free. */
public final class MainActivity extends Activity {

    private interface Job {
        String run() throws Exception;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText host, port, user, pass, name, poll, battery, tsKey, tsHost;
    private CheckBox tls, tlsInsecure, tsOn;
    private TextView output;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Settings.enabled(this)) KeepAliveJob.schedule(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        heading("BYD → Home Assistant");
        note("Version " + appVersion());
        note("Sends your car's data to Home Assistant over MQTT. Needs Wireless ADB turned on in "
                + "the head unit's developer settings. The first time you press Save & start, "
                + "tap Allow (and tick \"Always allow\") on the ADB prompt.");

        heading("MQTT broker");
        host = field("Broker address (HA's Tailscale IP, e.g. 100.x.y.z)", InputType.TYPE_CLASS_TEXT);
        port = field("Port", InputType.TYPE_CLASS_NUMBER);
        user = field("Username", InputType.TYPE_CLASS_TEXT);
        pass = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tls = check("Use TLS (not needed with Tailscale)");
        tlsInsecure = check("Trust self-signed certificate");

        heading("Tailscale");
        tsOn = check("Connect through Tailscale");
        tsKey = field("Auth key (tskey-auth-…), needed once", InputType.TYPE_CLASS_TEXT);
        tsHost = field("Device name on your tailnet", InputType.TYPE_CLASS_TEXT);

        heading("Car");
        name = field("Name in Home Assistant", InputType.TYPE_CLASS_TEXT);
        poll = field("Update every N seconds", InputType.TYPE_CLASS_NUMBER);
        battery = field("Battery size in kWh (Seal U DM-i: 18.3)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        LinearLayout row1 = row();
        button(row1, "Save & start", v -> {
            Config c = readForm();
            Settings.save(this, c);
            Settings.setEnabled(this, true);
            KeepAliveJob.schedule(this);
            runJob("Connecting to ADB… if a prompt appears, tap Allow.", () -> DaemonControl.start(this, c));
        });
        button(row1, "Stop", v -> {
            Settings.setEnabled(this, false);
            runJob("Stopping…", () -> DaemonControl.stop(this));
        });
        LinearLayout row2 = row();
        button(row2, "Status", v -> runJob("Reading status…", () -> DaemonControl.status(this)));
        button(row2, "Log", v -> runJob("Reading log…", () -> DaemonControl.log(this)));
        button(row2, "Data dump", v -> runJob("Reading dump…", () -> DaemonControl.dump(this)));

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(12), 0, 0);
        root.addView(output);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        fillForm(Settings.load(this));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void runJob(String waiting, Job job) {
        output.setText(waiting);
        io.execute(() -> {
            String text;
            try {
                text = job.run();
            } catch (Exception e) {
                text = "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            }
            final String t = text;
            ui.post(() -> output.setText(t));
        });
    }

    private void fillForm(Config c) {
        host.setText(c.brokerHost);
        port.setText(String.valueOf(c.brokerPort));
        user.setText(c.username);
        pass.setText(c.password);
        tls.setChecked(c.tls);
        tlsInsecure.setChecked(c.tlsInsecure);
        tsOn.setChecked(c.tailscaleEnabled);
        tsKey.setText(c.tailscaleAuthKey);
        tsHost.setText(c.tailscaleHostname);
        name.setText(c.vehicleName);
        poll.setText(String.valueOf(c.pollSeconds));
        battery.setText(String.valueOf(c.batteryKwh));
    }

    private Config readForm() {
        Config c = Settings.load(this);
        c.brokerHost = host.getText().toString().trim();
        c.brokerPort = parseInt(port, c.tls ? 8883 : 1883);
        c.username = user.getText().toString().trim();
        c.password = pass.getText().toString();
        c.tls = tls.isChecked();
        c.tlsInsecure = tlsInsecure.isChecked();
        c.tailscaleEnabled = tsOn.isChecked();
        c.tailscaleAuthKey = tsKey.getText().toString().trim();
        c.tailscaleHostname = tsHost.getText().toString().trim().isEmpty()
                ? "byd-seal-u" : tsHost.getText().toString().trim();
        c.vehicleName = name.getText().toString().trim().isEmpty()
                ? "BYD Seal U DM-i" : name.getText().toString().trim();
        c.pollSeconds = Math.max(5, parseInt(poll, 30));
        try {
            c.batteryKwh = Double.parseDouble(battery.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            c.batteryKwh = 18.3;
        }
        return c;
    }

    private static int parseInt(EditText e, int dflt) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    // ---------- tiny view helpers ----------

    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (build " + pi.versionCode + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        root.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        root.addView(t);
    }

    private EditText field(String hint, int type) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setPadding(0, dp(8), 0, 0);
        root.addView(label);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private CheckBox check(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        root.addView(c);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        root.addView(r);
        return r;
    }

    private void button(LinearLayout row, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
