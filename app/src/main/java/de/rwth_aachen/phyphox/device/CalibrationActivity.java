package de.rwth_aachen.phyphox.device;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import de.rwth_aachen.phyphox.R;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//Measures the constant clock-domain offset between camera frame timestamps and sensor
//timestamps on THIS device. A sharp tap spikes the accelerometer and shakes the camera
//frame at the same real instant; pairing the two detections yields the offset directly.
//Frame-quantized pairing is noisy (~1-2 frames per pair), so we collect many taps, show
//every pair to the user, suggest the median, and let the user adjust before applying.
public class CalibrationActivity extends AppCompatActivity implements SensorEventListener {

    private static final int REQ_CAMERA = 71;
    private static final int MIN_PAIRS = 5;                 //more taps -> median converges
    private static final double TAP_G = 28.0;               //m/s^2; a pickup peaks ~15, a real tap 60+
    private static final long TAP_GAP_NS = 400_000_000L;    //retrigger gap for tap clustering
    private static final long WINDOW_NS = 900_000_000L;     //pairing window around each accel tap
    private static final int LUMA_STEP = 8;                 //subsample stride on the Y plane
    private static final double LUMA_JUMP = 1.8;            //shake-induced luma diff

    private SensorManager sensorManager;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private PreviewView preview;
    private TextView status, result, history;
    private EditText edit;
    private Button button, apply;
    private android.widget.LinearLayout pairsBox;

    private boolean measuring = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Long> accelOnsets = new ArrayList<>();   //sensor-domain nanos
    private final List<Double> lumaSeries = new ArrayList<>();
    private final List<Long> lumaTimes = new ArrayList<>();     //camera-domain nanos
    private double prevLuma = Double.NaN;
    private long lastAccelOnset = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Phyphox_DayNight);
        setContentView(R.layout.activity_calibration);
        BottomNavHelper.bind(this, R.id.nav_device);

        preview = findViewById(R.id.calibPreview);
        status = findViewById(R.id.calibStatus);
        result = findViewById(R.id.calibResult);
        history = findViewById(R.id.calibHistory);
        edit = findViewById(R.id.calibEdit);
        apply = findViewById(R.id.calibApply);
        pairsBox = findViewById(R.id.calibPairs);
        button = findViewById(R.id.calibStart);
        button.setOnClickListener(v -> toggle());
        apply.setOnClickListener(v -> applyUserValue());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        cameraExecutor = Executors.newSingleThreadExecutor();
        showHistory();
    }

    private void showHistory() {
        if (CalibrationStore.hasCalibration(this)) {
            long ns = CalibrationStore.cameraBiasNs(this);
            history.setText(getString(R.string.phyerma_calib_last,
                    ns / 1e6, CalibrationStore.sampleCount(this),
                    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",
                            CalibrationStore.calibratedAtMs(this))));
        } else {
            history.setText(R.string.phyerma_calib_never);
        }
    }

    private void toggle() {
        if (measuring) {
            stopMeasurement();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return;
            }
            startMeasurement();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            startMeasurement();
        else if (requestCode == REQ_CAMERA)
            status.setText(R.string.phyerma_calib_need_camera);
    }

    private void startMeasurement() {
        accelOnsets.clear();
        lumaSeries.clear();
        lumaTimes.clear();
        prevLuma = Double.NaN;
        lastAccelOnset = 0;
        measuring = true;
        button.setText(R.string.phyerma_calib_stop);
        result.setText("");
        edit.setVisibility(View.GONE);
        apply.setVisibility(View.GONE);
        status.setText(R.string.phyerma_calib_running);

        Sensor accel = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (accel == null) {
            status.setText(R.string.phyerma_calib_no_accel);
            measuring = false;
            return;
        }
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis ia = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new android.util.Size(320, 240))
                        .build();
                ia.setAnalyzer(cameraExecutor, this::onFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, ia);
            } catch (Exception e) {
                status.setText(getString(R.string.phyerma_calib_camera_fail, e.getMessage()));
                stopMeasurement();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onFrame(ImageProxy image) {
        try {
            if (!measuring)
                return;
            long ts = image.getImageInfo().getTimestamp();
            double luma = meanLuma(image);
            if (!Double.isNaN(prevLuma)) {
                lumaSeries.add(Math.abs(luma - prevLuma));
                lumaTimes.add(ts);
            }
            prevLuma = luma;
        } finally {
            image.close();
        }
    }

    private static double meanLuma(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int h = image.getHeight(), w = image.getWidth();
        long sum = 0;
        int n = 0;
        for (int y = 0; y < h; y += LUMA_STEP) {
            int base = y * rowStride;
            for (int x = 0; x < w; x += LUMA_STEP) {
                sum += buf.get(base + x * pixelStride) & 0xff;
                n++;
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!measuring || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER)
            return;
        double mag = Math.sqrt(event.values[0] * event.values[0]
                + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
        if (mag > TAP_G && event.timestamp - lastAccelOnset > TAP_GAP_NS) {
            lastAccelOnset = event.timestamp;
            accelOnsets.add(event.timestamp);
            int n = accelOnsets.size();
            ui.post(() -> status.setText(getString(R.string.phyerma_calib_taps, n)));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void stopMeasurement() {
        measuring = false;
        button.setText(R.string.phyerma_calib_start);
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        evaluate();
    }

    //The frame whose exposure straddles the tap carries the shake; mid-exposure is its best
    //single-point estimate, so each pair contributes (frameTs + halfFrame) - accelTs.
    private void evaluate() {
        List<Long> camPeaks = new ArrayList<>();
        long halfFrameNs = estimateFramePeriodNs() / 2;
        for (int i = 1; i + 1 < lumaSeries.size(); i++) {
            double d = lumaSeries.get(i);
            if (d > LUMA_JUMP && d >= lumaSeries.get(i - 1) && d >= lumaSeries.get(i + 1)) {
                long ts = lumaTimes.get(i);
                if (camPeaks.isEmpty() || ts - camPeaks.get(camPeaks.size() - 1) > TAP_GAP_NS)
                    camPeaks.add(ts);
            }
        }
        //Pass 1: rough pairing gets a median anchor; pass 2 re-pairs within ±300 ms of it so
        //a missed luma peak or an off-by-one bind cannot drag every later pair along.
        List<Long> biasNs = pairWithin(accelOnsets, camPeaks, WINDOW_NS, halfFrameNs);
        if (biasNs.size() >= MIN_PAIRS) {
            Collections.sort(biasNs);
            long anchor = biasNs.get(biasNs.size() / 2);
            List<Long> refined = new ArrayList<>();
            for (long a : accelOnsets) {
                long best = -1, bestDist = Long.MAX_VALUE;
                for (long c : camPeaks) {
                    long bias = c + halfFrameNs - a;
                    long dist = Math.abs(bias - anchor);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = c;
                    }
                }
                if (best >= 0 && bestDist < 300_000_000L)
                    refined.add(best + halfFrameNs - a);
            }
            if (refined.size() >= MIN_PAIRS)
                biasNs = refined;
        }
        if (biasNs.size() < MIN_PAIRS) {
            status.setText(getString(R.string.phyerma_calib_few_taps, biasNs.size()));
            return;
        }
        Collections.sort(biasNs);
        //One checkbox per measured pair - everything on by default; unchecking outliers
        //updates the suggestion live. Nothing checked = median over all pairs.
        pairsBox.removeAllViews();
        pairsBox.setTag(biasNs);
        for (long b : biasNs) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(String.format(Locale.US, "%+d ms", Math.round(b / 1e6)));
            cb.setChecked(true);
            cb.setTag(b);
            cb.setOnCheckedChangeListener((v, on) -> updateSuggestion());
            pairsBox.addView(cb);
        }
        pairsBox.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.phyerma_calib_done, biasNs.size()));
        result.setText("");
        updateSuggestion();
        edit.setVisibility(View.VISIBLE);
        apply.setVisibility(View.VISIBLE);
    }

    private void updateSuggestion() {
        List<Long> sel = new ArrayList<>();
        List<Long> all = (List<Long>) pairsBox.getTag();
        for (int i = 0; i < pairsBox.getChildCount(); i++) {
            android.widget.CheckBox cb = (android.widget.CheckBox) pairsBox.getChildAt(i);
            if (cb.isChecked())
                sel.add((Long) cb.getTag());
        }
        if (sel.isEmpty())
            sel = all;
        if (sel == null || sel.isEmpty())
            return;
        Collections.sort(sel);
        long median = sel.get(sel.size() / 2);
        edit.setText(String.format(Locale.US, "%d", Math.round(-median / 1e6)));
        status.setText(getString(R.string.phyerma_calib_suggest, sel.size()));
    }

    private void applyUserValue() {
        String s = edit.getText().toString().trim();
        long ms;
        try {
            ms = Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            status.setText(R.string.phyerma_calib_bad_value);
            return;
        }
        CalibrationStore.saveCameraBias(this, ms * 1_000_000L, accelOnsets.size());
        result.append("\n" + getString(R.string.phyerma_calib_applied, (double) ms));
        edit.setVisibility(View.GONE);
        apply.setVisibility(View.GONE);
        showHistory();
    }

    private List<Long> pairWithin(List<Long> accelOnsets, List<Long> camPeaks,
                                  long windowNs, long halfFrameNs) {
        List<Long> camLeft = new ArrayList<>(camPeaks);
        List<Long> out = new ArrayList<>();
        for (long a : accelOnsets) {
            long best = -1, bestDist = Long.MAX_VALUE;
            for (long c : camLeft) {
                long dist = Math.abs(c + halfFrameNs - a);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
            if (best >= 0 && bestDist < windowNs) {
                camLeft.remove(best);
                out.add(best + halfFrameNs - a);
            }
        }
        return out;
    }

    private long estimateFramePeriodNs() {
        if (lumaTimes.size() < 4)
            return 33_000_000L;
        return (lumaTimes.get(lumaTimes.size() - 1) - lumaTimes.get(0)) / (lumaTimes.size() - 1);
    }

    @Override
    protected void onPause() {
        if (measuring)
            stopMeasurement();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null)
            cameraExecutor.shutdown();
    }
}
