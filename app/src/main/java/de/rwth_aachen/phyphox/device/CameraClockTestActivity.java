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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import de.rwth_aachen.phyphox.R;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//Measures the constant clock-domain offset between camera frame timestamps and sensor
//timestamps on THIS device, for the lens the user picks. A sharp tap spikes the accelerometer
//and shakes the camera frame at the same real instant; pairing the two detections yields the
//offset directly (the maths is in CameraClockPairing). Frame-quantized pairing is noisy
//(~1-2 frames per pair), so we collect many taps, show every pair to the user, suggest the
//median, and let the user adjust before applying.
public class CameraClockTestActivity extends AppCompatActivity implements SensorEventListener {

    private static final int REQ_CAMERA = 71;
    private static final double TAP_G = 28.0;               //m/s^2; a pickup peaks ~15, a real tap 60+
    private static final int LUMA_STEP = 8;                 //subsample stride on the Y plane

    private SensorManager sensorManager;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private PreviewView preview;
    private TextView status, result;
    private EditText edit;
    private Button button, apply;
    private RadioGroup lensGroup;
    private LinearLayout pairsBox;

    private volatile boolean measuring = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Long> accelOnsets = new ArrayList<>();   //sensor-domain nanos
    private final List<Double> lumaSeries = new ArrayList<>();
    private final List<Long> lumaTimes = new ArrayList<>();     //camera-domain nanos
    private double prevLuma = Double.NaN;
    private long lastAccelOnset = 0;
    private String measuredLens = CalibrationStore.LENS_BACK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Phyphox_DayNight);
        setContentView(R.layout.activity_camera_clock_test);

        //Edge-to-edge: keep the page's own 16dp padding and add the system bars on top of it.
        View root = findViewById(R.id.calibRoot);
        final int pad = root.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        preview = findViewById(R.id.calibPreview);
        status = findViewById(R.id.calibStatus);
        result = findViewById(R.id.calibResult);
        edit = findViewById(R.id.calibEdit);
        apply = findViewById(R.id.calibApply);
        pairsBox = findViewById(R.id.calibPairs);
        lensGroup = findViewById(R.id.calibLens);
        button = findViewById(R.id.calibStart);
        button.setOnClickListener(v -> toggle());
        apply.setOnClickListener(v -> applyUserValue());
        findViewById(R.id.calibBack).setOnClickListener(v -> finish());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private String selectedLens() {
        return lensGroup.getCheckedRadioButtonId() == R.id.calibLensFront
                ? CalibrationStore.LENS_FRONT : CalibrationStore.LENS_BACK;
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
        Sensor accel = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (accel == null) {
            status.setText(R.string.phyerma_calib_no_accel);
            return;
        }
        synchronized (lumaSeries) {
            accelOnsets.clear();
            lumaSeries.clear();
            lumaTimes.clear();
            prevLuma = Double.NaN;
            lastAccelOnset = 0;
        }
        measuredLens = selectedLens();
        measuring = true;
        button.setText(R.string.phyerma_calib_stop);
        result.setText("");
        //A new measurement must not leave the previous one's pairs, suggestion or apply button
        //on screen: if this run finds too few taps they would still look current.
        pairsBox.removeAllViews();
        pairsBox.setVisibility(View.GONE);
        edit.setVisibility(View.GONE);
        apply.setVisibility(View.GONE);
        for (int i = 0; i < lensGroup.getChildCount(); i++)
            lensGroup.getChildAt(i).setEnabled(false);
        status.setText(R.string.phyerma_calib_running);

        //FASTEST, not GAME: at ~50 Hz the first sample over the threshold trails the real onset
        //by half a sample interval (~10 ms) on average, a bias the median cannot remove.
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);

        CameraSelector selector = CalibrationStore.LENS_FRONT.equals(measuredLens)
                ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
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
                cameraProvider.bindToLifecycle(this, selector, p, ia);
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
            synchronized (lumaSeries) {
                if (!Double.isNaN(prevLuma)) {
                    lumaSeries.add(Math.abs(luma - prevLuma));
                    lumaTimes.add(ts);
                }
                prevLuma = luma;
            }
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
        if (mag > TAP_G && event.timestamp - lastAccelOnset > CameraClockPairing.TAP_GAP_NS) {
            lastAccelOnset = event.timestamp;
            int n;
            synchronized (lumaSeries) {
                accelOnsets.add(event.timestamp);
                n = accelOnsets.size();
            }
            ui.post(() -> status.setText(getString(R.string.phyerma_calib_taps, n)));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void stopMeasurement() {
        measuring = false;
        button.setText(R.string.phyerma_calib_start);
        for (int i = 0; i < lensGroup.getChildCount(); i++)
            lensGroup.getChildAt(i).setEnabled(true);
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        evaluate();
    }

    private void evaluate() {
        List<Long> onsets, peaks;
        long halfFrameNs;
        synchronized (lumaSeries) {
            onsets = new ArrayList<>(accelOnsets);
            peaks = CameraClockPairing.shakePeaks(lumaSeries, lumaTimes);
            halfFrameNs = CameraClockPairing.framePeriodNs(lumaTimes) / 2;
        }
        List<Long> biasNs = CameraClockPairing.pair(onsets, peaks, halfFrameNs);
        if (biasNs.size() < CameraClockPairing.MIN_PAIRS) {
            status.setText(getString(R.string.phyerma_calib_few_taps, biasNs.size(), CameraClockPairing.MIN_PAIRS));
            return;
        }
        //One checkbox per measured pair - everything on by default; unchecking outliers
        //updates the suggestion live. Nothing checked = median over all pairs.
        pairsBox.removeAllViews();
        for (long b : biasNs) {
            CheckBox cb = new CheckBox(this);
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

    //The pairs the user left ticked; all of them when none is.
    private List<Long> selectedPairs() {
        List<Long> sel = new ArrayList<>(), all = new ArrayList<>();
        for (int i = 0; i < pairsBox.getChildCount(); i++) {
            CheckBox cb = (CheckBox) pairsBox.getChildAt(i);
            all.add((Long) cb.getTag());
            if (cb.isChecked())
                sel.add((Long) cb.getTag());
        }
        return sel.isEmpty() ? all : sel;
    }

    private void updateSuggestion() {
        List<Long> sel = selectedPairs();
        if (sel.isEmpty())
            return;
        edit.setText(String.format(Locale.US, "%d", Math.round(-CameraClockPairing.median(sel) / 1e6)));
        status.setText(getString(R.string.phyerma_calib_suggest, sel.size()));
    }

    private void applyUserValue() {
        //Accept a decimal comma as well: it is what many keyboards offer.
        String s = edit.getText().toString().trim().replace(',', '.');
        long ms;
        try {
            ms = Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            status.setText(R.string.phyerma_calib_bad_value);
            return;
        }
        CalibrationStore.saveCameraBias(this, measuredLens, ms * 1_000_000L, selectedPairs().size());
        result.setText(getString(R.string.phyerma_calib_applied, (double) ms));
        pairsBox.removeAllViews();
        pairsBox.setVisibility(View.GONE);
        edit.setVisibility(View.GONE);
        apply.setVisibility(View.GONE);
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
