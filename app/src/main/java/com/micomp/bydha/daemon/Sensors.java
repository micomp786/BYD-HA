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
