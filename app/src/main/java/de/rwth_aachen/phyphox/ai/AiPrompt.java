package de.rwth_aachen.phyphox.ai;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

//Builds the system message: the fixed instructions plus what is specific to this phone and to the
//experiment the user wants changed.
public final class AiPrompt {
    private AiPrompt() {}

    //phyphox sensor type name -> Android sensor type, as listed in assets/ai/system_prompt.txt
    static Map<String, Integer> sensorTypes() {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("accelerometer", Sensor.TYPE_ACCELEROMETER);
        types.put("linear_acceleration", Sensor.TYPE_LINEAR_ACCELERATION);
        types.put("gravity", Sensor.TYPE_GRAVITY);
        types.put("gyroscope", Sensor.TYPE_GYROSCOPE);
        types.put("magnetic_field", Sensor.TYPE_MAGNETIC_FIELD);
        types.put("pressure", Sensor.TYPE_PRESSURE);
        types.put("light", Sensor.TYPE_LIGHT);
        types.put("proximity", Sensor.TYPE_PROXIMITY);
        types.put("temperature", Sensor.TYPE_AMBIENT_TEMPERATURE);
        types.put("humidity", Sensor.TYPE_RELATIVE_HUMIDITY);
        types.put("attitude", Sensor.TYPE_ROTATION_VECTOR);
        return types;
    }

    public static String build(Context context, String targetTitle, String targetXml) throws IOException {
        StringBuilder prompt = new StringBuilder(readAsset(context, "ai/system_prompt.txt"));
        prompt.append("\n# This phone\n");
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        StringBuilder have = new StringBuilder();
        StringBuilder missing = new StringBuilder();
        for (Map.Entry<String, Integer> type : sensorTypes().entrySet()) {
            boolean present = sensors != null && sensors.getDefaultSensor(type.getValue()) != null;
            StringBuilder list = present ? have : missing;
            if (list.length() > 0)
                list.append(", ");
            list.append(type.getKey());
        }
        prompt.append("Sensors available: ").append(have.length() == 0 ? "none" : have).append(".\n");
        if (missing.length() > 0)
            prompt.append("Sensors NOT available: ").append(missing).append(".\n");
        PackageManager pm = context.getPackageManager();
        prompt.append("Microphone: ").append(pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ? "yes" : "no")
                .append(". GPS: ").append(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ? "yes" : "no").append(".\n");

        if (targetXml == null) {
            prompt.append("\n# Task\nCreate a NEW experiment unless the user asks a question.\n");
        } else {
            prompt.append("\n# Task\nThe user wants to MODIFY this existing experiment (\"").append(targetTitle)
                    .append("\"). Return the complete modified file. This is its current file:\n```xml\n")
                    .append(targetXml.trim()).append("\n```\n");
        }
        return prompt.toString();
    }

    private static String readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1)
                out.write(chunk, 0, n);
            return out.toString("UTF-8");
        }
    }
}
