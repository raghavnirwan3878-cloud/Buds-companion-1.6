package com.budscompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 1;
    private static final long OPEN_REFRESH_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText, deviceText, firmwareText, modelText, historyText, usageText, errorText;
    private TextView leftBatteryValue, rightBatteryValue, caseBatteryValue;
    private TextView leftChargingState, rightChargingState, caseChargingState;
    private ProgressBar leftBatteryBar, rightBatteryBar, caseBatteryBar;
    private MaterialSwitch gameSwitch;
    private boolean refreshing;

    private final Runnable openRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            requestBatteryRefresh();
            handler.postDelayed(this, OPEN_REFRESH_MS);
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        deviceText = findViewById(R.id.device_text);
        modelText = findViewById(R.id.model_text);
        firmwareText = findViewById(R.id.firmware_text);
        historyText = findViewById(R.id.history_text);
        usageText = findViewById(R.id.usage_text);
        errorText = findViewById(R.id.error_text);
        leftBatteryValue = findViewById(R.id.left_battery_value);
        rightBatteryValue = findViewById(R.id.right_battery_value);
        caseBatteryValue = findViewById(R.id.case_battery_value);
        leftChargingState = findViewById(R.id.left_charging_state);
        rightChargingState = findViewById(R.id.right_charging_state);
        caseChargingState = findViewById(R.id.case_charging_state);
        leftBatteryBar = findViewById(R.id.left_battery_progress);
        rightBatteryBar = findViewById(R.id.right_battery_progress);
        caseBatteryBar = findViewById(R.id.case_battery_progress);
        gameSwitch = findViewById(R.id.game_mode_switch);

        MaterialButton choose = findViewById(R.id.choose_device_button);
        MaterialButton start = findViewById(R.id.start_service_button);
        MaterialButton refresh = findViewById(R.id.refresh_button);
        MaterialButton threshold = findViewById(R.id.threshold_button);
        MaterialButton info = findViewById(R.id.info_refresh_button);

        choose.setOnClickListener(v -> chooseDeviceDialog());
        start.setOnClickListener(v -> requestPermissionsThenStart());
        refresh.setOnClickListener(v -> requestBatteryRefresh());
        info.setOnClickListener(v -> requestInfoRefresh());
        threshold.setOnClickListener(v -> showThresholdDialog());
        gameSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(BudsConnectionService.PREF_GAME_MODE, checked).apply();
            Toast.makeText(this, checked ? "Game mode enabled" : "Game mode disabled", Toast.LENGTH_SHORT).show();
        });

        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, updateReceiver,
                new IntentFilter(BudsConnectionService.ACTION_LEVELS_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        handler.removeCallbacks(openRefresh);
        handler.post(openRefresh);
        requestInfoRefresh();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(openRefresh);
        try { unregisterReceiver(updateReceiver); } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    private void requestBatteryRefresh() {
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        if (!p.getBoolean(BudsConnectionService.PREF_CONNECTED, false)) return;
        startService(new Intent(this, BudsConnectionService.class)
                .setAction(BudsConnectionService.ACTION_REFRESH_BATTERY));
    }

    private void requestInfoRefresh() {
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        if (!p.getBoolean(BudsConnectionService.PREF_CONNECTED, false)) return;
        startService(new Intent(this, BudsConnectionService.class)
                .setAction(BudsConnectionService.ACTION_REFRESH_INFO));
    }

    private void refreshStatus() {
        if (refreshing) return;
        refreshing = true;
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        String mac = p.getString(BudsConnectionService.PREF_MAC, null);
        boolean connected = p.getBoolean(BudsConnectionService.PREF_CONNECTED, false);
        statusText.setText(connected ? "Connected" : "Disconnected");
        statusText.setAlpha(connected ? 1f : .65f);
        deviceText.setText(mac != null ? resolveDeviceName(mac) : "No device selected");
        modelText.setText(mac != null ? "Model / device: " + resolveDeviceNameOnly(mac) : "Model / device: —");
        firmwareText.setText("Firmware: " + p.getString(BudsConnectionService.PREF_FIRMWARE, "Not reported"));

        int l = p.getInt(BudsConnectionService.PREF_LEFT, -1);
        int r = p.getInt(BudsConnectionService.PREF_RIGHT, -1);
        int c = p.getInt(BudsConnectionService.PREF_CASE, -1);
        long caseTs = p.getLong(BudsConnectionService.PREF_CASE_TS, 0);
        boolean caseFresh = System.currentTimeMillis() - caseTs < 25_000L;
        animateBattery(leftBatteryValue, leftBatteryBar, l);
        animateBattery(rightBatteryValue, rightBatteryBar, r);
        animateBattery(caseBatteryValue, caseBatteryBar, c > 0 && caseFresh ? c : -1);
        leftChargingState.setText(p.getBoolean(BudsConnectionService.PREF_CHARGING_LEFT, false) ? "Charging" : "Ready");
        rightChargingState.setText(p.getBoolean(BudsConnectionService.PREF_CHARGING_RIGHT, false) ? "Charging" : "Ready");
        caseChargingState.setText(p.getBoolean(BudsConnectionService.PREF_CHARGING_CASE, false) ? "Charging" : "Ready");
        gameSwitch.setOnCheckedChangeListener(null);
        gameSwitch.setChecked(p.getBoolean(BudsConnectionService.PREF_GAME_MODE, false));
        gameSwitch.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(BudsConnectionService.PREF_GAME_MODE, checked).apply());

        int threshold = p.getInt(BudsConnectionService.PREF_LOW_THRESHOLD, 20);
        findViewById(R.id.threshold_button).setContentDescription("Low battery threshold " + threshold + " percent");
        ((MaterialButton) findViewById(R.id.threshold_button)).setText("Low battery warning: " + threshold + "%");

        renderHistory(p.getString(BudsConnectionService.PREF_HISTORY, ""));
        errorText.setText(connected ? "" : "Connect your paired earbuds to start live data.");
        refreshing = false;
    }

    private void animateBattery(TextView value, ProgressBar bar, int level) {
        int target = level > 0 ? Math.min(level, 100) : 0;
        value.setText(level > 0 ? level + "%" : "—");
        bar.setVisibility(level > 0 ? View.VISIBLE : View.INVISIBLE);
        int from = bar.getProgress();
        if (from == target) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, target);
        a.setDuration(420);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(v -> bar.setProgress((Integer) v.getAnimatedValue()));
        a.start();
        value.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110)
                .withEndAction(() -> value.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
    }

    private void renderHistory(String raw) {
        if (raw == null || raw.isEmpty()) {
            historyText.setText("No battery history yet.");
            usageText.setText("Usage history will appear after the app records readings.");
            return;
        }
        String[] rows = raw.split("\\n");
        int count = 0, firstL = -1, lastL = -1, firstR = -1, lastR = -1;
        long firstTs = 0, lastTs = 0;
        StringBuilder recent = new StringBuilder();
        for (int i = rows.length - 1; i >= 0 && count < 8; i--) {
            String[] x = rows[i].split(",");
            if (x.length < 4) continue;
            try {
                long ts = Long.parseLong(x[0]);
                int l = Integer.parseInt(x[1]), r = Integer.parseInt(x[2]);
                if (lastTs == 0) lastTs = ts;
                firstTs = ts;
                if (lastL < 0 && l > 0) lastL = l;
                if (lastR < 0 && r > 0) lastR = r;
                if (l > 0) firstL = l;
                if (r > 0) firstR = r;
                recent.append(DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(new Date(ts)))
                        .append("  L ").append(l > 0 ? l + "%" : "—")
                        .append("  R ").append(r > 0 ? r + "%" : "—").append("\n");
                count++;
            } catch (NumberFormatException ignored) {}
        }
        historyText.setText(recent.length() == 0 ? "No valid readings." : recent.toString().trim());
        long duration = Math.max(0, lastTs - firstTs);
        usageText.setText("Recorded samples: " + rows.length + "\nWindow: " + formatDuration(duration)
                + "\nObserved battery change: L " + deltaText(firstL, lastL) + " · R " + deltaText(firstR, lastR));
    }

    private String deltaText(int first, int last) {
        if (first <= 0 || last <= 0) return "—";
        int d = last - first;
        return (d > 0 ? "+" : "") + d + "%";
    }

    private String formatDuration(long ms) {
        long min = ms / 60000L;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    private void showThresholdDialog() {
        final String[] values = {"5", "10", "15", "20", "25", "30", "40", "50"};
        int current = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE)
                .getInt(BudsConnectionService.PREF_LOW_THRESHOLD, 20);
        int checked = 3;
        for (int i = 0; i < values.length; i++) if (Integer.parseInt(values[i]) == current) checked = i;
        new AlertDialog.Builder(this).setTitle("Low battery warning")
                .setSingleChoiceItems(values, checked, (d, which) -> {
                    int v = Integer.parseInt(values[which]);
                    getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE).edit()
                            .putInt(BudsConnectionService.PREF_LOW_THRESHOLD, v).apply();
                    d.dismiss();
                    refreshStatus();
                }).show();
    }

    private String resolveDeviceNameOnly(String mac) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return mac;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) {
                BluetoothDevice d = a.getRemoteDevice(mac);
                String n = d.getName();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        return mac;
    }

    private String resolveDeviceName(String mac) { return "Device: " + resolveDeviceNameOnly(mac) + "  •  " + mac; }

    private void chooseDeviceDialog() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) { requestPermissionsThenStart(); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { Toast.makeText(this, "No Bluetooth adapter", Toast.LENGTH_LONG).show(); return; }
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) { Toast.makeText(this, "Bluetooth permission needed", Toast.LENGTH_LONG).show(); return; }
        if (bonded.isEmpty()) { Toast.makeText(this, "Pair your earbuds in Android Bluetooth settings first", Toast.LENGTH_LONG).show(); return; }
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            try { names[i] = devices.get(i).getName() + "  (" + devices.get(i).getAddress() + ")"; }
            catch (SecurityException e) { names[i] = devices.get(i).getAddress(); }
        }
        new AlertDialog.Builder(this).setTitle("Select your earbuds").setItems(names, (dialog, which) -> {
            String mac = devices.get(which).getAddress();
            getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE).edit().putString(BudsConnectionService.PREF_MAC, mac).apply();
            refreshStatus();
            Toast.makeText(this, "Saved. Start monitoring to connect.", Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void requestPermissionsThenStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) { ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS); return; }
        startService();
    }

    private void startService() {
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        if (p.getString(BudsConnectionService.PREF_MAC, null) == null) { Toast.makeText(this, "Choose your earbuds first", Toast.LENGTH_SHORT).show(); return; }
        ContextCompat.startForegroundService(this, new Intent(this, BudsConnectionService.class));
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) startService();
    }

    private boolean hasPermission(String permission) { return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED; }
}
