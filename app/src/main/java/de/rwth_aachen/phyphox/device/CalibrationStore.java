package de.rwth_aachen.phyphox.device;

import android.content.Context;
import android.content.SharedPreferences;

//What the calibration panel remembers about THIS phone.
//
//Camera clock: the camera HAL timestamps on some devices tick in a domain offset from the
//sensor/elapsed clock. The offset is a hardware property of one lens on one phone, so it is
//measured here (CameraClockTestActivity) rather than looked up - the device database only
//carries reference values, and the size varies by phone and lens (figures of 100-200 ms quoted
//earlier for one vivo V2452A came from different measurements and are not a specification).
//One offset is kept per lens, because the front and back cameras
//need not share a clock. The back-lens keys are the ones earlier builds already wrote, so an
//existing calibration keeps working.
//
//Export switches: which panel items go into the meta/ of every export. Off until the user
//ticks them; the one exception is a camera offset that is actually applied to frame times,
//which video.json always records because it changes the numbers the reader sees.
public final class CalibrationStore {
    private static final String PREFS = "phyphox";

    public static final String LENS_BACK = "back";
    public static final String LENS_FRONT = "front";

    //Items of the calibration panel that can be switched into the export meta.
    public static final String ITEM_DEVICE = "device";
    public static final String ITEM_CAMERA_CLOCK = "camera_clock";

    private CalibrationStore() {}

    //Maps CameraSelector.LENS_FACING_* (0 = front, 1 = back) onto a store key without pulling
    //CameraX into this class.
    public static String lensKey(int lensFacing) {
        return lensFacing == 0 ? LENS_FRONT : LENS_BACK;
    }

    private static String suffix(String lens) {
        return LENS_FRONT.equals(lens) ? "_front" : "";
    }

    //Signed nanoseconds to add to a camera-frame timestamp so it lands on the sensor timeline.
    public static long cameraBiasNs(Context context, String lens) {
        return prefs(context).getLong("calib_camera_bias_ns" + suffix(lens), 0L);
    }

    public static boolean hasCalibration(Context context, String lens) {
        return prefs(context).contains("calib_camera_bias_ns" + suffix(lens));
    }

    public static long calibratedAtMs(Context context, String lens) {
        return prefs(context).getLong("calib_camera_bias_at_ms" + suffix(lens), 0L);
    }

    public static int sampleCount(Context context, String lens) {
        return prefs(context).getInt("calib_camera_bias_samples" + suffix(lens), 0);
    }

    //Saving a calibration also ticks its export switch the first time, so a measured value is
    //not silently left out of the meta; unticking it afterwards is remembered.
    public static void saveCameraBias(Context context, String lens, long biasNs, int samples) {
        String s = suffix(lens);
        SharedPreferences.Editor e = prefs(context).edit()
                .putLong("calib_camera_bias_ns" + s, biasNs)
                .putLong("calib_camera_bias_at_ms" + s, System.currentTimeMillis())
                .putInt("calib_camera_bias_samples" + s, samples);
        if (!prefs(context).contains(includeKey(ITEM_CAMERA_CLOCK)))
            e.putBoolean(includeKey(ITEM_CAMERA_CLOCK), true);
        e.apply();
    }

    public static void clearCameraBias(Context context, String lens) {
        String s = suffix(lens);
        prefs(context).edit()
                .remove("calib_camera_bias_ns" + s)
                .remove("calib_camera_bias_at_ms" + s)
                .remove("calib_camera_bias_samples" + s)
                .apply();
    }

    public static boolean isIncluded(Context context, String item) {
        return prefs(context).getBoolean(includeKey(item), false);
    }

    public static void setIncluded(Context context, String item, boolean included) {
        prefs(context).edit().putBoolean(includeKey(item), included).apply();
    }

    private static String includeKey(String item) {
        return "calib_include_" + item;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
