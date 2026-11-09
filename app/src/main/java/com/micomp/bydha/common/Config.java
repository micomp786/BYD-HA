package com.micomp.bydha.common;

import org.json.JSONException;
import org.json.JSONObject;

/** User settings. Stored as JSON by the app and pushed to {@link Paths#CONFIG} for the daemon. */
public final class Config {
    public String brokerHost = "";
    public int brokerPort = 1883;
    public String username = "";
    public String password = "";
    public boolean tls = false;
    public boolean tlsInsecure = false;

    /** Short id used in topics and HA unique ids. */
    public String vehicleId = "seal_u";
    public String vehicleName = "BYD Seal U DM-i";
    public String baseTopic = "bydha";
    public String discoveryPrefix = "homeassistant";
    public int pollSeconds = 30;

    public boolean tailscaleEnabled = false;
    public String tailscaleAuthKey = "";
    public String tailscaleHostname = "byd-seal-u";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("brokerHost", brokerHost);
            o.put("brokerPort", brokerPort);
            o.put("username", username);
            o.put("password", password);
            o.put("tls", tls);
            o.put("tlsInsecure", tlsInsecure);
            o.put("vehicleId", vehicleId);
            o.put("vehicleName", vehicleName);
            o.put("baseTopic", baseTopic);
            o.put("discoveryPrefix", discoveryPrefix);
            o.put("pollSeconds", pollSeconds);
            o.put("tailscaleEnabled", tailscaleEnabled);
            o.put("tailscaleAuthKey", tailscaleAuthKey);
            o.put("tailscaleHostname", tailscaleHostname);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static Config fromJson(String json) {
        Config c = new Config();
        if (json == null || json.trim().isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(json);
            c.brokerHost = o.optString("brokerHost", c.brokerHost).trim();
            c.brokerPort = o.optInt("brokerPort", c.brokerPort);
            c.username = o.optString("username", c.username);
            c.password = o.optString("password", c.password);
            c.tls = o.optBoolean("tls", c.tls);
            c.tlsInsecure = o.optBoolean("tlsInsecure", c.tlsInsecure);
            c.vehicleId = sanitizeId(o.optString("vehicleId", c.vehicleId));
            c.vehicleName = o.optString("vehicleName", c.vehicleName);
            c.baseTopic = o.optString("baseTopic", c.baseTopic).trim();
            c.discoveryPrefix = o.optString("discoveryPrefix", c.discoveryPrefix).trim();
            c.pollSeconds = Math.max(5, Math.min(3600, o.optInt("pollSeconds", c.pollSeconds)));
            c.tailscaleEnabled = o.optBoolean("tailscaleEnabled", c.tailscaleEnabled);
            c.tailscaleAuthKey = o.optString("tailscaleAuthKey", c.tailscaleAuthKey).trim();
            c.tailscaleHostname = o.optString("tailscaleHostname", c.tailscaleHostname).trim();
        } catch (JSONException ignored) {
            // Fall back to defaults; the daemon reports "not configured".
        }
        return c;
    }

    public static String sanitizeId(String s) {
        String id = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
        return id.isEmpty() ? "seal_u" : id;
    }

    public String topic(String leaf) {
        return baseTopic + "/" + vehicleId + "/" + leaf;
    }
}
