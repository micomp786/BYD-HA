package com.micomp.bydha.daemon;

import com.micomp.bydha.common.Config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Home Assistant MQTT discovery payloads (one retained config message per entity). */
final class Discovery {
    private Discovery() {}

    /** topic -> payload. */
    static Map<String, String> build(Config c, String version) throws JSONException {
        Map<String, String> out = new LinkedHashMap<>();
        String node = "bydha_" + c.vehicleId;
        JSONObject device = new JSONObject()
                .put("identifiers", new JSONArray().put(node))
                .put("name", c.vehicleName)
                .put("manufacturer", "BYD")
                .put("model", "Seal U DM-i")
                .put("sw_version", version);

        for (Sensors.Def d : Sensors.ALL) {
            JSONObject p = new JSONObject()
                    .put("name", d.name)
                    .put("unique_id", node + "_" + d.key)
                    .put("state_topic", c.topic("state"))
                    .put("value_template", "{{ value_json." + d.key + " }}")
                    .put("availability_topic", c.topic("availability"))
                    .put("device", device);
            if (d.deviceClass != null) p.put("device_class", d.deviceClass);
            if (d.stateClass != null) p.put("state_class", d.stateClass);
            if (d.unit != null) p.put("unit_of_measurement", d.unit);
            if (d.icon != null) p.put("icon", d.icon);
            if (d.diagnostic) p.put("entity_category", "diagnostic");
            if ("binary_sensor".equals(d.component)) {
                p.put("payload_on", "ON").put("payload_off", "OFF");
            }
            if ("gear".equals(d.key)) {
                p.put("options", new JSONArray().put("P").put("R").put("N").put("D").put("M").put("S"));
            } else if ("charging_gun".equals(d.key)) {
                p.put("options", new JSONArray().put("none").put("ac").put("dc").put("ac_dc").put("v2l"));
            }
            out.put(c.discoveryPrefix + "/" + d.component + "/" + node + "/" + d.key + "/config", p.toString());
        }

        JSONObject tracker = new JSONObject()
                .put("name", "Location")
                .put("unique_id", node + "_location")
                .put("json_attributes_topic", c.topic("location"))
                .put("source_type", "gps")
                .put("icon", "mdi:car")
                .put("availability_topic", c.topic("availability"))
                .put("device", device);
        out.put(c.discoveryPrefix + "/device_tracker/" + node + "/location/config", tracker.toString());

        JSONObject dumpButton = new JSONObject()
                .put("name", "Send debug dump")
                .put("unique_id", node + "_dump")
                .put("command_topic", c.topic("cmd"))
                .put("payload_press", "dump")
                .put("entity_category", "diagnostic")
                .put("icon", "mdi:bug")
                .put("availability_topic", c.topic("availability"))
                .put("device", device);
        out.put(c.discoveryPrefix + "/button/" + node + "/dump/config", dumpButton.toString());
        return out;
    }
}
