package de.rwth_aachen.phyphox.device;

import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.rwth_aachen.phyphox.R;

public class SensorLabActivity extends AppCompatActivity implements SensorEventListener {
    public static final String EXTRA_TYPE = "sensor_type";
    public static final String EXTRA_NAME = "sensor_name";

    private SensorManager sensorManager;
    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (adapter != null)
                adapter.notifyDataSetChanged();
            ui.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Phyphox_DayNight);
        setContentView(R.layout.activity_sensor_lab);
        BottomNavHelper.bind(this, R.id.nav_sensors);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        DeviceProfile match = DeviceDatabase.get(this).matchThisPhone();
        if (sensorManager != null) {
            for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL))
                rows.add(new Row(s, match));
        }

        //Formerly its own bottom-nav page: device identity + sensor-library match now head
        //the sensor list so the middle tab can host timestamp calibration.
        String brand = DeviceDatabase.displayBrand(Build.BRAND, Build.MANUFACTURER);
        ((TextView) findViewById(R.id.deviceCardTitle)).setText(
                brand.isEmpty() ? Build.MODEL : brand + " · " + Build.MODEL);
        StringBuilder card = new StringBuilder();
        card.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(DeviceDatabase.guessOsFlavor()).append('\n');
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
        ((TextView) findViewById(R.id.deviceCardBody)).setText(card.toString());

        RecyclerView list = findViewById(R.id.sensorList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter(rows, row -> {
            Intent intent = new Intent(this, SensorDetailActivity.class);
            intent.putExtra(EXTRA_TYPE, row.sensor.getType());
            intent.putExtra(EXTRA_NAME, row.sensor.getName());
            startActivity(intent);
        });
        list.setAdapter(adapter);
        ((TextView) findViewById(R.id.sensorLabIntro)).setText(
                getString(R.string.phyerma_sensor_intro, rows.size()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            for (Row row : rows)
                sensorManager.registerListener(this, row.sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        for (Row row : rows) {
            if (row.sensor == event.sensor) {
                row.last = event.values.clone();
                row.window.add(event.timestamp, event.values);
                return;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    static final class Row {
        final Sensor sensor;
        final String baseline;
        final SampleWindow window = new SampleWindow();
        float[] last;

        Row(Sensor sensor, DeviceProfile match) {
            this.sensor = sensor;
            this.baseline = SensorCatalog.dbBaseline(match, sensor.getType());
        }
    }

    interface OnRowClick {
        void onClick(Row row);
    }

    static final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<Row> rows;
        private final OnRowClick click;

        Adapter(List<Row> rows, OnRowClick click) {
            this.rows = rows;
            this.click = click;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sensor_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row row = rows.get(position);
            String title = h.itemView.getContext().getString(SensorCatalog.titleRes(row.sensor.getType()));
            String unit = SensorCatalog.siUnit(row.sensor.getType());
            h.name.setText(title + "  ·  " + row.sensor.getName());
            h.meta.setText(row.sensor.getVendor() + "   type=" + row.sensor.getType()
                    + (unit.isEmpty() ? "" : "   " + unit));
            if (row.last != null && row.last.length > 0) {
                if (SensorCatalog.is3d(row.sensor.getType()) && row.last.length >= 3) {
                    double abs = Math.sqrt(row.last[0] * row.last[0] + row.last[1] * row.last[1] + row.last[2] * row.last[2]);
                    h.value.setText(String.format(Locale.US, "x=% .5f  y=% .5f  z=% .5f  |v|=%.5f %s",
                            row.last[0], row.last[1], row.last[2], abs, unit));
                } else {
                    h.value.setText(String.format(Locale.US, "%.5f %s", row.last[0], unit));
                }
            } else {
                h.value.setText("—");
            }
            SampleWindow.Snapshot snap = row.window.snapshot();
            String extra = snap.n >= 2 ? snap.formatRate() : "";
            if (row.baseline != null)
                extra = extra.isEmpty() ? row.baseline : extra + "\n" + row.baseline;
            h.extra.setText(extra);
            h.itemView.setOnClickListener(v -> click.onClick(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView name, meta, value, extra;
            VH(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.sensorRowName);
                meta = itemView.findViewById(R.id.sensorRowMeta);
                value = itemView.findViewById(R.id.sensorRowValue);
                extra = itemView.findViewById(R.id.sensorRowExtra);
            }
        }
    }
}
