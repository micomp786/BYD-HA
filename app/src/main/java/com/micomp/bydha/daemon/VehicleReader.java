package com.micomp.bydha.daemon;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw BYD SDK readings into the state published to Home Assistant.
 *
 * <p>Getter names, feature ids, value ranges and "invalid" encodings come from Overdrive's
 * BydDataCollector (MIT), which has been checked on a Sealion 6 / Seal U DM-i. Every value is
 * range-checked; anything implausible is published as null (unknown in HA) rather than a
 * wrong number.
 */
final class VehicleReader {
    // Statistic feature ids for the manager fallback (BYD Statistic.* constants).
    static final int STAT_TOTAL_MILEAGE = 1246765072;
    static final int STAT_MILEAGE_EV = 1246773284;
    static final int STAT_MILEAGE_HEV = 1246773264;
    static final int STAT_ELEC_PERCENTAGE = 1246777400;
    static final int STAT_FUEL_PERCENTAGE = 1246785600;
    static final int STAT_ELEC_DRIVING_RANGE = 1246765118;
    static final int STAT_FUEL_DRIVING_RANGE = 1246773304;

    private static final String[] GEARS = {null, "P", "R", "N", "D", "M", "S"};
    private static final String[] GUNS = {null, "none", "ac", "dc", "ac_dc", "v2l"};

    private final Byd byd;
    private final Context ctx;
    private Map<String, Object> lastLocation;

    private final double batteryKwh;

    VehicleReader(Byd byd, Context ctx, double batteryKwh) {
        this.byd = byd;
        this.ctx = ctx;
        this.batteryKwh = batteryKwh;
    }

    Map<String, Object> read() {
        Map<String, Object> s = new LinkedHashMap<>();

        // ---------- Traction battery ----------
        Double soc = num(byd.get(Byd.STATISTIC, "getElecPercentageValue"));
        if (!in(soc, 0.1, 100)) soc = intToD(byd.managerInt(Byd.STATISTIC, STAT_ELEC_PERCENTAGE));
        s.put("soc", in(soc, 0.1, 100) ? round(soc, 1) : null);
        // The car's own remaining-energy register mirrors SOC on this firmware (868 at 87 %),
        // so estimate from SOC and the configured battery size instead.
        s.put("battery_kwh", in(soc, 0.1, 100) && batteryKwh > 0 ? round(soc / 100.0 * batteryKwh, 1) : null);

        Double evRange = num(byd.get(Byd.STATISTIC, "getElecDrivingRangeValue"));
        if (!in(evRange, 0, 2000)) evRange = intToD(byd.managerInt(Byd.STATISTIC, STAT_ELEC_DRIVING_RANGE));
        s.put("ev_range_km", in(evRange, 0, 2000) ? (long) Math.round(evRange) : null);

        // ---------- Fuel ----------
        Double fuelPct = num(byd.get(Byd.STATISTIC, "getFuelPercentageValue"));
        if (!validFuelPct(fuelPct)) fuelPct = intToD(byd.managerInt(Byd.STATISTIC, STAT_FUEL_PERCENTAGE));
        s.put("fuel_pct", validFuelPct(fuelPct) ? (long) Math.round(fuelPct) : null);

        Double fuelRange = num(byd.get(Byd.STATISTIC, "getFuelDrivingRangeValue"));
        if (!in(fuelRange, 0, 1200)) fuelRange = intToD(byd.managerInt(Byd.STATISTIC, STAT_FUEL_DRIVING_RANGE));
        s.put("fuel_range_km", in(fuelRange, 0, 1200) ? (long) Math.round(fuelRange) : null);

        Object ev = s.get("ev_range_km"), fu = s.get("fuel_range_km");
        s.put("total_range_km", ev != null && fu != null ? (Long) ev + (Long) fu : null);

        // Values above 8000 are the HAL's "engine off / no reading" rail (seen: 8151 while parked).
        Double rpm = num(byd.get(Byd.ENGINE, "getEngineSpeed"));
        s.put("engine_rpm", in(rpm, 0, 8000) ? (long) Math.round(rpm) : null);

        // ---------- Distances ----------
        Double odo = num(byd.get(Byd.STATISTIC, "getTotalMileageValue"));
        if (!in(odo, 0.1, 9_999_999.9)) odo = intToD(byd.managerInt(Byd.STATISTIC, STAT_TOTAL_MILEAGE));
        if (in(odo, 0.1, 9_999_999.9)) {
            // Some trims report this register in 0.1 km; no real odometer reaches 1,000,000.
            if (odo >= 1_000_000) odo = odo / 10.0;
            s.put("odometer_km", round(odo, 1));
        } else {
            s.put("odometer_km", null);
        }
        s.put("ev_mileage_km", mileage(byd.get(Byd.STATISTIC, "getEVMileageValue"), STAT_MILEAGE_EV));
        s.put("hev_mileage_km", mileage(byd.get(Byd.STATISTIC, "getHEVMileageValue"), STAT_MILEAGE_HEV));

        Double totalElec = num(byd.get(Byd.STATISTIC, "getTotalElecConValue"));
        s.put("total_elec_kwh", in(totalElec, 0, 1_676_721.4) ? round(totalElec, 1) : null);
        Double totalFuel = num(byd.get(Byd.STATISTIC, "getTotalFuelConValue"));
        s.put("total_fuel_l", in(totalFuel, 0, 104_857.4) ? round(totalFuel, 1) : null);
        Double avgElec = num(byd.get(Byd.STATISTIC, "getTotalElecConPHMValue"));
        s.put("avg_elec_kwh_100km", in(avgElec, 0.1, 100) ? round(avgElec, 1) : null);
        Double avgFuel = num(byd.get(Byd.STATISTIC, "getTotalFuelConPHMValue"));
        s.put("avg_fuel_l_100km", in(avgFuel, 0.1, 50) ? round(avgFuel, 1) : null);

        // ---------- Current trip (instrument cluster) ----------
        Double tripKm = num(byd.get(Byd.INSTRUMENT, "getCurrentJourneyDriveMileage"));
        s.put("trip_km", in(tripKm, 0, 10_000) ? round(tripKm, 1) : null);
        Double tripH = num(byd.get(Byd.INSTRUMENT, "getCurrentJourneyDriveTime"));
        s.put("trip_hours", in(tripH, 0, 500) ? round(tripH, 1) : null);
        Double last50 = num(byd.get(Byd.INSTRUMENT, "getLast50KmPowerConsume"));
        s.put("last50_elec_kwh_100km", in(last50, 0.1, 100) ? round(last50, 1) : null);

        // ---------- Driving ----------
        Double speed = num(byd.get(Byd.SPEED, "getCurrentSpeed"));
        s.put("speed_kmh", in(speed, 0, 300) ? round(speed, 1) : null);
        Double gear = num(byd.get(Byd.GEARBOX, "getGearboxAutoModeType"));
        s.put("gear", gear != null && gear >= 1 && gear < GEARS.length ? GEARS[gear.intValue()] : null);
        Double outside = num(byd.get(Byd.INSTRUMENT, "getOutCarTemperature"));
        s.put("outside_temp_c", in(outside, -40, 70) ? round(outside, 1) : null);

        // ---------- Charging ----------
        Double gun = num(byd.get(Byd.CHARGING, "getChargingGunState"));
        String gunName = gun != null && gun >= 1 && gun < GUNS.length ? GUNS[gun.intValue()] : null;
        s.put("charging_gun", gunName);
        // On the Seal U DM-i Charging.getChargingPower stays 0 while AC charging; the cluster's
        // Instrument.getChargePower carries the real figure. Take the largest valid reading.
        Double powerKw = null;
        for (Object[] src : new Object[][]{
                {Byd.INSTRUMENT, "getChargePower"},
                {Byd.CHARGING, "getChargingPower"},
                {Byd.CHARGING, "getChargePower"}}) {
            Double v = num(byd.get((String) src[0], (String) src[1]));
            if (in(v, 0, 400) && (powerKw == null || v > powerKw)) powerKw = round(v, 2);
        }
        s.put("charge_power_kw", powerKw);
        boolean plugged = gunName != null && !"none".equals(gunName) && !"v2l".equals(gunName);
        s.put("plugged_in", gunName == null ? null : (plugged ? "ON" : "OFF"));
        if (gunName == null) {
            s.put("is_charging", null);
        } else {
            s.put("is_charging", plugged && powerKw != null && powerKw > 0.15 ? "ON" : "OFF");
        }

        // ---------- Tyres (kPa), wheel order 1=FL 2=FR 3=RL 4=RR ----------
        String[] wheels = {"fl", "fr", "rl", "rr"};
        for (int i = 0; i < 4; i++) {
            Double p = num(byd.get(Byd.TYRE, "getTyrePressureValue", i + 1));
            if (!in(p, 50, 500)) p = num(byd.get(Byd.TYRE, "getTyrePressureValueByType", i + 1));
            s.put("tyre_" + wheels[i] + "_kpa", in(p, 50, 500) ? (long) Math.round(p) : null);
        }

        readExtras(s);

        // ---------- 12 V ----------
        Double v12 = num(byd.get(Byd.OTA, "getBatteryPowerVoltage"));
        s.put("volt_12v", in(v12, 0.1, 20) ? round(v12, 2) : null);

        return s;
    }

    private static final String[] ENERGY_MODES = {"stop", "ev", "force_ev", "hev", "fuel", "keep"};

    /**
     * Fault and warning lights, with the value each one showed on a healthy Seal U DM-i in the
     * first debug dump. The firmware does not document these encodings and they differ per lamp,
     * so a light counts as "on" when it reports anything other than its healthy value or 0.
     */
    private static final Object[][] WARNINGS = {
            {"warn_low_fuel", "getLowFuelWarnLightState", 1},
            {"warn_low_12v", "getLowPowerBatteryWarnLightState", 1},
            {"warn_engine", "getEngineFailWarnLightState", 2},
            {"warn_abs", "getABSFaultWarningLight", 2},
            {"warn_esp", "getESPFailWarnLightState", 2},
            {"warn_airbag", "getSRSFaultWarningLight", 2},
            {"warn_brake", "getBrakeSysFaultLightState", 2},
            {"warn_traction_battery", "getPowerBatFailWarnLightState", 2},
            {"warn_steering", "getSteeringSYSFailWarnLightState", 2},
    };

    private void readExtras(Map<String, Object> s) {
        // Energy / drive mode
        Double em = num(byd.get(Byd.ENERGY, "getEnergyMode"));
        s.put("energy_mode", em != null && em >= 0 && em < ENERGY_MODES.length ? ENERGY_MODES[em.intValue()] : null);
        Double evLamp = num(byd.get(Byd.INSTRUMENT, "getEVIndicatorState"));
        Double hevLamp = num(byd.get(Byd.INSTRUMENT, "getHEVIndicatorState"));
        String drive = null;
        if (evLamp != null && evLamp == 1) drive = "EV";
        else if (hevLamp != null && hevLamp == 1) drive = "HEV";
        s.put("drive_mode", drive);
        Object rpm = s.get("engine_rpm");
        s.put("engine_running", rpm == null ? null : (((Long) rpm) > 0 ? "ON" : "OFF"));

        Double avgSpeed = num(byd.get(Byd.INSTRUMENT, "getAverageSpeed"));
        s.put("avg_speed_kmh", in(avgSpeed, 0, 300) ? (long) Math.round(avgSpeed) : null);
        Double drivingH = num(byd.get(Byd.STATISTIC, "getDrivingTimeValue"));
        s.put("driving_time_h", in(drivingH, 0, 1_000_000) ? round(drivingH, 1) : null);

        // Climate
        Double ac = num(byd.get(Byd.AC, "getAcStartState"));
        s.put("ac_on", ac == null || (ac != 0 && ac != 1) ? null : (ac == 1 ? "ON" : "OFF"));
        Double fan = num(byd.get(Byd.AC, "getAcWindLevel"));
        s.put("ac_fan_level", in(fan, 0, 10) ? (long) Math.round(fan) : null);

        // Body
        Double roofPos = num(byd.get(Byd.BODYWORK, "getSunroofPosition"));
        s.put("sunroof_position", in(roofPos, 0, 100) ? (long) Math.round(roofPos) : null);
        s.put("sunroof_state_raw", rawInt(byd.get(Byd.BODYWORK, "getSunroofState")));
        s.put("handbrake_raw", rawInt(byd.get(Byd.GEARBOX, "getEPBState")));
        // OTA device driver-door lock: 1 = unlocked, 2 = locked (Overdrive DOOR_STATE_*).
        Double lock = num(byd.get(Byd.OTA, "getLFDoorLockState"));
        s.put("door_unlocked", lock == null || (lock != 1 && lock != 2) ? null : (lock == 1 ? "ON" : "OFF"));

        // Warning lights
        for (Object[] w : WARNINGS) {
            Long v = rawInt(byd.get(Byd.INSTRUMENT, (String) w[1]));
            int healthy = (Integer) w[2];
            s.put((String) w[0], v == null ? null : (v == 0 || v == healthy ? "OFF" : "ON"));
        }

        // Other
        s.put("wireless_charger_raw", rawInt(byd.get(Byd.CHARGING, "getWirelessChargingState")));
        Double sched = num(byd.get(Byd.CHARGING, "getChargingScheduleEnableState"));
        s.put("scheduled_charging", sched == null || (sched != 0 && sched != 1) ? null : (sched == 1 ? "ON" : "OFF"));
    }

    /** Raw enum value, with the HAL's "no value" encodings (incl. 65535) dropped. */
    private static Long rawInt(Object o) {
        Double d = num(o);
        if (d == null || d == 65535 || d < 0) return null;
        return (long) Math.round(d);
    }

    private Long mileage(Object getter, int featureId) {
        Double v = num(getter);
        if (!usableMileage(v)) v = intToD(byd.managerInt(Byd.STATISTIC, featureId));
        return usableMileage(v) ? (long) Math.round(v) : null;
    }

    private static boolean usableMileage(Double v) {
        // 0xFFFFF is the statistic HAL's "not reported" value.
        return in(v, 1, 2_000_000) && v != 1_048_575 && v != 65535;
    }

    private static boolean validFuelPct(Double v) {
        // 0 is a real reading (empty tank); the HAL's 254/255 rails fall outside 0..100.
        return in(v, 0, 100);
    }

    // ---------- Location ----------

    private static final Pattern DUMPSYS_LOC =
            Pattern.compile("Location\\[(gps|network|fused) (-?\\d+\\.\\d+),(-?\\d+\\.\\d+)([^\\]]*)\\]");
    private static final Pattern DUMPSYS_ACC = Pattern.compile("\\b(?:hAcc|acc)=([\\d.]+)");

    /** GPS fix for the HA device tracker, or null. Tries LocationManager, then dumpsys. */
    Map<String, Object> location() {
        Location best = null;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                for (String p : new String[]{"gps", "fused", "network"}) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (best != null) {
            m.put("latitude", best.getLatitude());
            m.put("longitude", best.getLongitude());
            m.put("gps_accuracy", best.hasAccuracy() ? Math.round(best.getAccuracy()) : 50);
            lastLocation = m;
            return m;
        }
        Map<String, Object> fromDumpsys = locationFromDumpsys();
        if (fromDumpsys != null) lastLocation = fromDumpsys;
        return lastLocation;
    }

    private static Map<String, Object> locationFromDumpsys() {
        try {
            Process p = new ProcessBuilder("dumpsys", "location").redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 400_000) sb.append(line).append('\n');
            }
            p.waitFor();
            Matcher mt = DUMPSYS_LOC.matcher(sb);
            Map<String, Object> found = null;
            while (mt.find()) {
                boolean gps = "gps".equals(mt.group(1));
                if (found != null && !gps) continue;
                found = new LinkedHashMap<>();
                found.put("latitude", Double.parseDouble(mt.group(2)));
                found.put("longitude", Double.parseDouble(mt.group(3)));
                Matcher acc = DUMPSYS_ACC.matcher(mt.group(4));
                found.put("gps_accuracy", acc.find() ? Math.round(Double.parseDouble(acc.group(1))) : 50);
                if (gps) break;
            }
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers ----------

    static Double num(Object o) {
        if (!(o instanceof Number)) return null;
        double d = ((Number) o).doubleValue();
        return Byd.isSentinel(d) ? null : d;
    }

    static Double intToD(Integer i) {
        return i == null ? null : i.doubleValue();
    }

    static boolean in(Double v, double lo, double hi) {
        return v != null && v >= lo && v <= hi;
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
