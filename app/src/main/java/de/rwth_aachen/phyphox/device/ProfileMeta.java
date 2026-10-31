package de.rwth_aachen.phyphox.device;

import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

//The rows the calibration panel contributes to the meta of an export: only for the items the
//user ticked there, empty otherwise. Every row says where it came from, so a reader of the
//export can tell a measured value from a database reference and a raw value from a corrected one.
public final class ProfileMeta {
    private ProfileMeta() {}

    public static Map<String, String> collect(Context context) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (CalibrationStore.isIncluded(context, CalibrationStore.ITEM_DEVICE))
            addDevice(context, rows);
        if (CalibrationStore.isIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK))
            addCameraClock(context, rows);
        return rows;
    }

    private static void addDevice(Context context, Map<String, String> rows) {
        rows.put("device.os_skin", DeviceDatabase.guessOsFlavor());
        rows.put("device.android_release", String.valueOf(Build.VERSION.RELEASE));
        rows.put("device.android_api", String.valueOf(Build.VERSION.SDK_INT));
        DeviceProfile match = DeviceDatabase.get(context).matchThisPhone();
        if (match == null) {
            rows.put("device.db_match", "none");
            return;
        }
        rows.put("device.db_match", match.model);
        rows.put("device.db_submissions", String.valueOf(match.sampleSize));
        if (match.accelAvg != null)
            rows.put("device.db_accel_rest_g", String.format(Locale.US, "%.4f", match.accelAvg));
        if (match.accelStd != null)
            rows.put("device.db_accel_noise", String.format(Locale.US, "%.5f", match.accelStd));
        if (match.accelRate != null)
            rows.put("device.db_accel_rate_hz", String.format(Locale.US, "%.1f", match.accelRate));
        rows.put("device.db_note", "crowd-sourced reference from the phyphox sensor database; not applied to any reading");
    }

    private static void addCameraClock(Context context, Map<String, String> rows) {
        boolean any = false;
        for (String lens : new String[]{CalibrationStore.LENS_BACK, CalibrationStore.LENS_FRONT}) {
            if (!CalibrationStore.hasCalibration(context, lens))
                continue;
            any = true;
            String p = "camera_clock." + lens + ".";
            rows.put(p + "bias_ms", String.format(Locale.US, "%.3f", CalibrationStore.cameraBiasNs(context, lens) / 1e6));
            rows.put(p + "samples", String.valueOf(CalibrationStore.sampleCount(context, lens)));
            rows.put(p + "measured_at", isoUtc(CalibrationStore.calibratedAtMs(context, lens)));
        }
        if (!any) {
            rows.put("camera_clock.status", "not calibrated; camera frame times are as the camera reported them");
            return;
        }
        rows.put("camera_clock.note", "measured on this phone by tap pairing; bias_ms is already added to camera frame timestamps (<camera> t, video frameTimes)");
    }

    private static String isoUtc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
