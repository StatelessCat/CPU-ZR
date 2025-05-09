package com.example.cpuzr;          // adaptez si besoin

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView cpuTempView, battTempView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            updateTemperatures();
            ui.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        cpuTempView = findViewById(R.id.cpuTempView);
        battTempView = findViewById(R.id.battTempView);
    }

    @Override protected void onResume() { super.onResume(); ui.post(updater); }
    @Override protected void onPause()  { super.onPause();  ui.removeCallbacks(updater); }

    /* --------- Mise à jour UI --------- */
    private void updateTemperatures() {
        float cpu  = readCpuTemp();
        float batt = readBatteryTemp();

        cpuTempView.setText(String.format(Locale.getDefault(),
                "CPU\u00A0:\u00A0%s\u00A0°C", format(cpu)));
        battTempView.setText(String.format(Locale.getDefault(),
                "Batterie\u00A0:\u00A0%s\u00A0°C", format(batt)));
    }
    private String format(float t) {
        return Float.isNaN(t)          // ← appel correct
                ? "--"
                : String.format(Locale.getDefault(), "%.1f", t);
    }

    private float readCpuTemp() {
        // Tentative HPM protégée
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                HardwarePropertiesManager hpm =
                        (HardwarePropertiesManager) getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
                if (hpm != null) {
                    float[] t = hpm.getDeviceTemperatures(
                            HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                            HardwarePropertiesManager.TEMPERATURE_CURRENT);
                    if (t != null && t.length > 0 && t[0] > 0) return t[0];
                }
            } catch (SecurityException ignored) { /* API interdite */ }
        }
        // Plan B : /sys — peut renvoyer NaN si SELinux bloque
        return scanThermalZonesForCpu();
    }

    private float scanThermalZonesForCpu() {
        File dir = new File("/sys/class/thermal");

        // 1) dossier absent ou non-lisible → impossible
        if (!dir.exists() || !dir.canRead()) return Float.NaN;

        File[] zones = dir.listFiles();
        if (zones == null || zones.length == 0) return Float.NaN;

        for (File zone : zones) {
            File typeFile = new File(zone, "type");
            File tempFile = new File(zone, "temp");
            try (BufferedReader tr = new BufferedReader(new FileReader(typeFile))) {
                String type = tr.readLine();
                if (type != null && type.toLowerCase().contains("cpu")) {
                    try (BufferedReader br = new BufferedReader(new FileReader(tempFile))) {
                        String line = br.readLine();
                        if (line != null) {
                            float raw = Float.parseFloat(line.trim());
                            return (raw > 1000f) ? raw / 1000f : raw; // m°C → °C éventuel
                        }
                    }
                }
            } catch (Exception ignored) { /* accès refusé ou format non conforme */ }
        }
        return Float.NaN;
    }


    /* --------- Lecture Batterie --------- */
    private float readBatteryTemp() {
        // Essai HPM (souvent autorisé pour BATTERY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                HardwarePropertiesManager hpm =
                        (HardwarePropertiesManager) getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
                if (hpm != null) {
                    float[] t = hpm.getDeviceTemperatures(
                            HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY,
                            HardwarePropertiesManager.TEMPERATURE_CURRENT);
                    if (t != null && t.length > 0 && t[0] > 0) return t[0];
                }
            } catch (SecurityException ignored) { /* on tentera le broadcast */ }
        }
        // Broadcast toujours disponible
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int tenth = (i != null) ? i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
        return (tenth > 0) ? tenth / 10f : Float.NaN;
    }
}
