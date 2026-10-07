package de.rwth_aachen.phyphox.device;

import android.content.Context;
import android.content.SharedPreferences;

//Per-device timestamp calibration: the camera HAL clock on some devices ticks in a domain
//offset from the sensor/elapsed clock (measured ~170 ms on a vivo V2452A). The offset is a
//hardware property, so it must be measured on THIS phone rather than looked up - the device
//database only carries a reference value for sanity-checking.
public final class CalibrationStore {
    private static final String PREFS = "phyphox";
    private static final String KEY_BIAS_NS = "calib_camera_bias_ns";
    private static final String KEY_WHEN_MS = "calib_camera_bias_at_ms";
    private static final String KEY_SAMPLES = "calib_camera_bias_samples";

    private CalibrationStore() {}

    //Signed nanoseconds to add to a camera-frame timestamp so it lands on the sensor timeline.
    public static long cameraBiasNs(Context context) {
        return prefs(context).getLong(KEY_BIAS_NS, 0L);
    }

    public static boolean hasCalibration(Context context) {
        return prefs(context).contains(KEY_BIAS_NS);
    }

    public static long calibratedAtMs(Context context) {
        return prefs(context).getLong(KEY_WHEN_MS, 0L);
    }

    public static int sampleCount(Context context) {
        return prefs(context).getInt(KEY_SAMPLES, 0);
    }

    public static void saveCameraBias(Context context, long biasNs, int samples) {
        prefs(context).edit()
                .putLong(KEY_BIAS_NS, biasNs)
                .putLong(KEY_WHEN_MS, System.currentTimeMillis())
                .putInt(KEY_SAMPLES, samples)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
