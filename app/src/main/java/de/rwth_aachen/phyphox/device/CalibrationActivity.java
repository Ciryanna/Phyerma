package de.rwth_aachen.phyphox.device;

import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import de.rwth_aachen.phyphox.R;

//The middle tab: what is specific to THIS phone. The device identity, and the calibration tests
//that measure it. Each item has a switch; the ticked ones are written into the meta of every
//export (ProfileMeta), so a recording carries the facts needed to read it later.
public class CalibrationActivity extends AppCompatActivity {

    private TextView clockStatus;
    private CheckBox includeDevice, includeClock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Phyphox_DayNight);
        setContentView(R.layout.activity_calibration);
        BottomNavHelper.bind(this, R.id.nav_device);

        showDevice();

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        int sensorCount = sensorManager == null ? 0 : sensorManager.getSensorList(Sensor.TYPE_ALL).size();
        ((TextView) findViewById(R.id.sensorsCardTitle)).setText(getString(R.string.phyerma_panel_sensors_title, sensorCount));
        findViewById(R.id.sensorsCard).setOnClickListener(v ->
                startActivity(new Intent(this, SensorLabActivity.class)));

        clockStatus = findViewById(R.id.clockStatus);
        includeDevice = findViewById(R.id.includeDevice);
        includeClock = findViewById(R.id.includeClock);
        includeDevice.setOnClickListener(v ->
                CalibrationStore.setIncluded(this, CalibrationStore.ITEM_DEVICE, includeDevice.isChecked()));
        includeClock.setOnClickListener(v ->
                CalibrationStore.setIncluded(this, CalibrationStore.ITEM_CAMERA_CLOCK, includeClock.isChecked()));
        findViewById(R.id.clockMeasure).setOnClickListener(v ->
                startActivity(new Intent(this, CameraClockTestActivity.class)));
        findViewById(R.id.clockClear).setOnClickListener(v -> {
            CalibrationStore.clearCameraBias(this, CalibrationStore.LENS_BACK);
            CalibrationStore.clearCameraBias(this, CalibrationStore.LENS_FRONT);
            refresh();
        });
    }

    @Override
    public void finish() {
        super.finish();
        BottomNavHelper.fade(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void showDevice() {
        String brand = DeviceDatabase.displayBrand(Build.BRAND, Build.MANUFACTURER);
        ((TextView) findViewById(R.id.deviceCardTitle)).setText(
                brand.isEmpty() ? Build.MODEL : brand + " · " + Build.MODEL);
        TextView body = findViewById(R.id.deviceCardBody);
        String head = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                + DeviceDatabase.guessOsFlavor() + '\n';
        body.setText(head);
        //The first lookup parses the 3 MB database; doing that here would hold the first frame back.
        new Thread(() -> {
            DeviceProfile match = DeviceDatabase.get(getApplicationContext()).matchThisPhone();
            StringBuilder card = new StringBuilder(head);
            if (match == null) {
                card.append(getString(R.string.phyerma_db_miss));
            } else {
                card.append(getString(R.string.phyerma_db_hit, match.model, match.sampleSize));
                if (match.accelAvg != null)
                    card.append(String.format(Locale.US, "\nrest-g = %.4f", match.accelAvg));
                if (match.accelStd != null)
                    card.append(String.format(Locale.US, "  σ = %.5f", match.accelStd));
                if (match.accelRate != null)
                    card.append(String.format(Locale.US, "  %.1f Hz", match.accelRate));
            }
            body.post(() -> body.setText(card.toString()));
        }, "device-db").start();
    }

    //Re-read the store: the test page is a separate screen and writes it while we are paused.
    private void refresh() {
        includeDevice.setChecked(CalibrationStore.isIncluded(this, CalibrationStore.ITEM_DEVICE));
        includeClock.setChecked(CalibrationStore.isIncluded(this, CalibrationStore.ITEM_CAMERA_CLOCK));

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String lens : new String[]{CalibrationStore.LENS_BACK, CalibrationStore.LENS_FRONT}) {
            if (!CalibrationStore.hasCalibration(this, lens))
                continue;
            any = true;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(getString(CalibrationStore.LENS_FRONT.equals(lens)
                            ? R.string.phyerma_calib_lens_front : R.string.phyerma_calib_lens_back))
                    .append(": ")
                    .append(getString(R.string.phyerma_calib_last,
                            CalibrationStore.cameraBiasNs(this, lens) / 1e6,
                            CalibrationStore.sampleCount(this, lens),
                            android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",
                                    CalibrationStore.calibratedAtMs(this, lens))));
        }
        clockStatus.setText(any ? sb.toString() : getString(R.string.phyerma_calib_never));
        findViewById(R.id.clockClear).setEnabled(any);
    }
}
