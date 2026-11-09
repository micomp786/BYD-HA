package com.micomp.bydha.daemon;

import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reflection access to the BYD SDK classes ({@code android.hardware.bydauto.*}) that exist on
 * the head unit's framework. Nothing here links against BYD code at compile time.
 *
 * <p>Every call goes through a worker thread with a timeout, because a wedged vehicle HAL can
 * block a binder call forever and must not freeze the whole daemon.
 */
final class Byd {
    static final String STATISTIC = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice";
    static final String INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    static final String SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";
    static final String GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    static final String CHARGING = "android.hardware.bydauto.charging.BYDAutoChargingDevice";
    static final String TYRE = "android.hardware.bydauto.tyre.BYDAutoTyreDevice";
    static final String ENGINE = "android.hardware.bydauto.engine.BYDAutoEngineDevice";
    static final String OTA = "android.hardware.bydauto.ota.BYDAutoOtaDevice";
    static final String BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
    static final String ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice";
    static final String AC = "android.hardware.bydauto.ac.BYDAutoAcDevice";
    static final String DOORLOCK = "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice";

    static final String[] ALL = {STATISTIC, INSTRUMENT, SPEED, GEARBOX, CHARGING, TYRE, ENGINE,
            OTA, BODYWORK, ENERGY, AC, DOORLOCK};

    // HAL "not available" encodings (from Overdrive's BydFeatureIds).
    static final int BMS_UNAVAILABLE = -10011;
    static final int INVALID_VALUE = -2147482645;
    static final int INVALID_VALUE_2 = -2147482648;

    private static final String TAG = "Byd";
    private static final long CALL_TIMEOUT_MS = 3000;
    private static final long RETRY_NULL_DEVICE_MS = 60_000;

    private final Context ctx;
    private final Map<String, Object> devices = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<String, String> deviceErrors = new ConcurrentHashMap<>();
    private ExecutorService worker = newWorker();
    private Object deviceManager;
    private boolean deviceManagerTried;
    private Object autoManager;
    private boolean autoManagerTried;

    Byd(Context ctx) {
        this.ctx = ctx;
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BydCall");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs {@code task} with a timeout. Returns null on timeout or failure. */
    private synchronized Object bounded(String what, java.util.concurrent.Callable<Object> task) {
        Future<Object> f = worker.submit(task);
        try {
            return f.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            Log.i(TAG, "timeout: " + what + " (replacing worker thread)");
            f.cancel(true);
            worker.shutdownNow();
            worker = newWorker();
            return null;
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
                c = c.getCause();
            }
            throw new CallFailed(c);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static final class CallFailed extends RuntimeException {
        CallFailed(Throwable cause) { super(cause); }
    }

    /** Device singleton for {@code className}, or null if this head unit does not provide it. */
    Object device(String className) {
        Object d = devices.get(className);
        if (d != null) return d;
        Long at = retryAt.get(className);
        if (at != null && SystemClock.elapsedRealtime() < at) return null;
        try {
            d = bounded("getInstance " + className, () -> {
                Class<?> cls = Class.forName(className);
                return cls.getMethod("getInstance", Context.class).invoke(null, ctx);
            });
        } catch (CallFailed e) {
            deviceErrors.put(className, describe(e.getCause()));
            d = null;
        }
        if (d == null) {
            deviceErrors.putIfAbsent(className, "getInstance returned null");
            retryAt.put(className, SystemClock.elapsedRealtime() + RETRY_NULL_DEVICE_MS);
            return null;
        }
        fixNullContext(d);
        devices.put(className, d);
        deviceErrors.remove(className);
        Log.i(TAG, "device ready: " + simple(className));
        return d;
    }

    Map<String, String> deviceErrors() {
        return new TreeMap<>(deviceErrors);
    }

    /** Some SDK builds leave {@code AbsBYDAutoDevice.mContext} null when created outside an app. */
    private void fixNullContext(Object device) {
        Class<?> c = device.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("mContext");
                f.setAccessible(true);
                if (f.get(device) == null) f.set(device, ctx);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    /** Calls a no-arg getter. Returns null if missing, failed, or timed out. */
    Object get(String deviceClass, String method) {
        Object d = device(deviceClass);
        if (d == null) return null;
        try {
            return bounded(method, () -> {
                Method m = d.getClass().getMethod(method);
                return m.invoke(d);
            });
        } catch (CallFailed e) {
            return null;
        }
    }

    /** Calls a getter that takes one int argument. */
    Object get(String deviceClass, String method, int arg) {
        Object d = device(deviceClass);
        if (d == null) return null;
        try {
            return bounded(method + "(" + arg + ")", () -> {
                Method m = d.getClass().getMethod(method, int.class);
                return m.invoke(d, arg);
            });
        } catch (CallFailed e) {
            return null;
        }
    }

    /**
     * Reads a feature id through BYD's system-service managers
     * ({@code BYDAutoDeviceManager.getInt(type, id)}, then {@code getSystemService("auto")}).
     * On DiLink 3 some per-device getters throw SecurityException while this path answers.
     */
    Integer managerInt(String deviceClass, int featureId) {
        Object d = device(deviceClass);
        if (d == null) return null;
        Integer type = deviceType(d);
        if (type == null) return null;
        Integer v = invokeInt(deviceManager(), type, featureId);
        if (v == null) v = invokeInt(autoManager(), type, featureId);
        return v;
    }

    private Integer invokeInt(Object mgr, int type, int featureId) {
        if (mgr == null) return null;
        try {
            Object r = bounded("getInt " + featureId, () ->
                    mgr.getClass().getMethod("getInt", int.class, int.class).invoke(mgr, type, featureId));
            if (r instanceof Number) {
                int i = ((Number) r).intValue();
                return isSentinel(i) ? null : i;
            }
        } catch (CallFailed ignored) {
        }
        return null;
    }

    private Integer deviceType(Object d) {
        for (String name : new String[]{"getDevicetype", "getType"}) {
            try {
                Object r = bounded(name, () -> d.getClass().getMethod(name).invoke(d));
                if (r instanceof Number && ((Number) r).intValue() > 0) return ((Number) r).intValue();
            } catch (CallFailed ignored) {
            }
        }
        return null;
    }

    private synchronized Object deviceManager() {
        if (!deviceManagerTried) {
            deviceManagerTried = true;
            try {
                deviceManager = bounded("BYDAutoDeviceManager", () ->
                        Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
                                .getMethod("getInstance", Context.class).invoke(null, ctx));
            } catch (CallFailed e) {
                deviceManager = null;
            }
        }
        return deviceManager;
    }

    private synchronized Object autoManager() {
        if (!autoManagerTried) {
            autoManagerTried = true;
            try {
                autoManager = bounded("getSystemService(auto)", () -> ctx.getSystemService("auto"));
            } catch (CallFailed e) {
                autoManager = null;
            }
        }
        return autoManager;
    }

    static boolean isSentinel(double v) {
        return Double.isNaN(v) || Double.isInfinite(v)
                || v == BMS_UNAVAILABLE || v == INVALID_VALUE || v == INVALID_VALUE_2
                || v == Integer.MIN_VALUE || v == Integer.MAX_VALUE;
    }

    /**
     * Calls every public no-arg getter that returns a primitive or String on every device, for
     * the debug dump. Used to map what this particular car and firmware actually reports.
     */
    Map<String, Object> dumpAll() {
        Map<String, Object> out = new TreeMap<>();
        for (String cls : ALL) {
            Object d = device(cls);
            String dev = simple(cls);
            if (d == null) {
                out.put(dev + ".ERROR", deviceErrors.getOrDefault(cls, "unavailable"));
                continue;
            }
            for (Method m : d.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!m.getName().startsWith("get") && !m.getName().startsWith("is")) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                if (!(rt.isPrimitive() || rt == String.class) || rt == void.class) continue;
                if (m.getName().equals("getClass")) continue;
                Object v;
                try {
                    v = bounded(m.getName(), () -> m.invoke(d));
                    if (v == null) v = "null/timeout";
                } catch (CallFailed e) {
                    v = "ERR " + describe(e.getCause());
                }
                out.put(dev + "." + m.getName(), v);
            }
        }
        return out;
    }

    static String simple(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1).replace("BYDAuto", "").replace("Device", "");
    }

    static String describe(Throwable t) {
        if (t == null) return "unknown";
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
