package com.micomp.bydha.daemon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The Home Assistant entities this app publishes. Keys match the state JSON. */
final class Sensors {
    private Sensors() {}

    static final class Def {
        final String key, name, component, deviceClass, stateClass, unit, icon;
        final boolean diagnostic;

        Def(String key, String name, String component, String deviceClass, String stateClass,
            String unit, String icon, boolean diagnostic) {
            this.key = key; this.name = name; this.component = component;
            this.deviceClass = deviceClass; this.stateClass = stateClass;
            this.unit = unit; this.icon = icon; this.diagnostic = diagnostic;
        }
    }

    private static final String M = "measurement", T = "total_increasing";
    static final List<Def> ALL;

    static {
        List<Def> l = new ArrayList<>();
        // Battery (plug-in hybrid traction battery)
        l.add(s("soc", "Battery", "battery", M, "%", "mdi:battery"));
        l.add(s("ev_range_km", "EV range", "distance", M, "km", "mdi:map-marker-distance"));
        l.add(s("charge_power_kw", "Charging power", "power", M, "kW", "mdi:ev-station"));
        l.add(s("charging_gun", "Charge plug", "enum", null, null, "mdi:power-plug"));
        l.add(b("is_charging", "Charging", "battery_charging", null));
        l.add(b("plugged_in", "Plugged in", "plug", null));
        // Fuel (DM-i petrol side)
        l.add(s("fuel_pct", "Fuel level", null, M, "%", "mdi:gas-station"));
        l.add(s("fuel_range_km", "Fuel range", "distance", M, "km", "mdi:gas-station"));
        l.add(s("total_range_km", "Total range", "distance", M, "km", "mdi:map-marker-distance"));
        l.add(s("engine_rpm", "Engine speed", null, M, "rpm", "mdi:engine"));
        // Distances and consumption
        l.add(s("odometer_km", "Odometer", "distance", T, "km", "mdi:counter"));
        l.add(s("ev_mileage_km", "EV distance (lifetime)", "distance", T, "km", "mdi:counter"));
        l.add(s("hev_mileage_km", "Hybrid distance (lifetime)", "distance", T, "km", "mdi:counter"));
        l.add(s("total_elec_kwh", "Electricity used (lifetime)", "energy", T, "kWh", "mdi:lightning-bolt"));
        l.add(s("total_fuel_l", "Fuel used (lifetime)", "volume", T, "L", "mdi:gas-station"));
        l.add(s("avg_elec_kwh_100km", "Avg electricity use", null, M, "kWh/100km", "mdi:chart-line"));
        l.add(s("avg_fuel_l_100km", "Avg fuel use", null, M, "L/100km", "mdi:chart-line"));
        // Current trip
        l.add(s("trip_km", "Trip distance", "distance", M, "km", "mdi:map-marker-path"));
        l.add(s("trip_hours", "Trip time", "duration", M, "h", "mdi:timer-outline"));
        l.add(s("last50_elec_kwh_100km", "Electricity use, last 50 km", null, M, "kWh/100km", "mdi:chart-line"));
        // Driving
        l.add(s("speed_kmh", "Speed", "speed", M, "km/h", "mdi:speedometer"));
        l.add(s("gear", "Gear", "enum", null, null, "mdi:car-shift-pattern"));
        l.add(s("outside_temp_c", "Outside temperature", "temperature", M, "°C", "mdi:thermometer"));
        // Tyres
        l.add(s("tyre_fl_kpa", "Tyre pressure front left", "pressure", M, "kPa", "mdi:car-tire-alert"));
        l.add(s("tyre_fr_kpa", "Tyre pressure front right", "pressure", M, "kPa", "mdi:car-tire-alert"));
        l.add(s("tyre_rl_kpa", "Tyre pressure rear left", "pressure", M, "kPa", "mdi:car-tire-alert"));
        l.add(s("tyre_rr_kpa", "Tyre pressure rear right", "pressure", M, "kPa", "mdi:car-tire-alert"));
        // Drive / energy
        l.add(s("drive_mode", "Drive mode", null, null, null, "mdi:car-electric"));
        l.add(s("energy_mode", "Energy mode", null, null, null, "mdi:leaf"));
        l.add(b("engine_running", "Engine running", "running", "mdi:engine"));
        l.add(s("avg_speed_kmh", "Average speed", "speed", M, "km/h", "mdi:speedometer-medium"));
        l.add(s("driving_time_h", "Driving time (lifetime)", "duration", T, "h", "mdi:timer"));
        // Climate and body
        l.add(b("ac_on", "A/C", "running", "mdi:air-conditioner"));
        l.add(s("ac_fan_level", "A/C fan level", null, M, null, "mdi:fan"));
        l.add(s("sunroof_position", "Sunroof position", null, M, "%", "mdi:window-shutter"));
        l.add(s("sunroof_state_raw", "Sunroof state (raw)", null, null, null, "mdi:window-shutter"));
        l.add(s("handbrake_raw", "Handbrake state (raw)", null, null, null, "mdi:car-brake-parking"));
        l.add(b("door_unlocked", "Driver door lock", "lock", "mdi:car-door-lock"));
        // Warning lights
        l.add(b("warn_low_fuel", "Low fuel warning", "problem", "mdi:gas-station-off"));
        l.add(b("warn_low_12v", "Low 12V battery warning", "problem", "mdi:car-battery"));
        l.add(b("warn_engine", "Engine fault light", "problem", "mdi:engine-off"));
        l.add(b("warn_abs", "ABS fault light", "problem", "mdi:car-brake-abs"));
        l.add(b("warn_esp", "ESP fault light", "problem", "mdi:car-traction-control"));
        l.add(b("warn_airbag", "Airbag fault light", "problem", "mdi:airbag"));
        l.add(b("warn_brake", "Brake system fault light", "problem", "mdi:car-brake-alert"));
        l.add(b("warn_traction_battery", "Traction battery fault light", "problem", "mdi:battery-alert"));
        l.add(b("warn_steering", "Steering fault light", "problem", "mdi:steering"));
        // Other
        l.add(s("wireless_charger_raw", "Phone charger state (raw)", null, null, null, "mdi:cellphone-wireless"));
        l.add(b("scheduled_charging", "Scheduled charging", null, "mdi:calendar-clock"));
        // 12 V
        l.add(s("volt_12v", "12V battery", "voltage", M, "V", "mdi:car-battery"));
        // Diagnostics
        l.add(d("tailscale", "Tailscale", "mdi:vpn"));
        l.add(d("last_update", "Last update", "mdi:clock-outline"));
        ALL = Collections.unmodifiableList(l);
    }

    private static Def s(String k, String n, String dc, String sc, String u, String i) {
        return new Def(k, n, "sensor", dc, sc, u, i, false);
    }

    private static Def b(String k, String n, String dc, String i) {
        return new Def(k, n, "binary_sensor", dc, null, null, i, false);
    }

    private static Def d(String k, String n, String i) {
        return new Def(k, n, "sensor", "last_update".equals(k) ? "timestamp" : null, null, null, i, true);
    }
}
