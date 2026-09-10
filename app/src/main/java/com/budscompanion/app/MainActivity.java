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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1;

    private TextView statusText;
    private TextView deviceText;
    private TextView debugText;
    private TextView debugStatusText;
    private TextView leftBatteryValue, rightBatteryValue, caseBatteryValue;
    private TextView leftChargingState, rightChargingState, caseChargingState;
    private android.widget.ProgressBar leftBatteryBar, rightBatteryBar, caseBatteryBar;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        deviceText = findViewById(R.id.device_text);
        debugText = findViewById(R.id.debug_text);
        debugStatusText = findViewById(R.id.debug_status_text);
        leftBatteryValue = findViewById(R.id.left_battery_value);
        rightBatteryValue = findViewById(R.id.right_battery_value);
        caseBatteryValue = findViewById(R.id.case_battery_value);
        leftChargingState = findViewById(R.id.left_charging_state);
        rightChargingState = findViewById(R.id.right_charging_state);
        caseChargingState = findViewById(R.id.case_charging_state);
        leftBatteryBar = findViewById(R.id.left_battery_progress);
        rightBatteryBar = findViewById(R.id.right_battery_progress);
        caseBatteryBar = findViewById(R.id.case_battery_progress);
        Button chooseDeviceBtn = findViewById(R.id.choose_device_button);
        Button startServiceBtn = findViewById(R.id.start_service_button);
        Button refreshBtn = findViewById(R.id.refresh_button);

        chooseDeviceBtn.setOnClickListener(v -> chooseDeviceDialog());
        startServiceBtn.setOnClickListener(v -> requestPermissionsThenStart());
        refreshBtn.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
            if (prefs.getBoolean(BudsConnectionService.PREF_CONNECTED, false)) {
                startService(new Intent(this, BudsConnectionService.class)
                        .setAction(BudsConnectionService.ACTION_REFRESH_BATTERY));
            }
            refreshStatus();
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(updateReceiver, new IntentFilter(BudsConnectionService.ACTION_LEVELS_UPDATED),
                Context.RECEIVER_NOT_EXPORTED);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(updateReceiver);
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        String mac = prefs.getString(BudsConnectionService.PREF_MAC, null);
        deviceText.setText(mac != null ? "Selected device: " + resolveDeviceName(mac) : "No device selected yet");

        boolean connected = prefs.getBoolean(BudsConnectionService.PREF_CONNECTED, false);
        statusText.setText(connected ? "Connected" : "Disconnected");

        int l = prefs.getInt(BudsConnectionService.PREF_LEFT, -1);
        int r = prefs.getInt(BudsConnectionService.PREF_RIGHT, -1);
        int c = prefs.getInt(BudsConnectionService.PREF_CASE, -1);
        long caseTs = prefs.getLong(BudsConnectionService.PREF_CASE_TS, 0);
        boolean caseFresh = (System.currentTimeMillis() - caseTs) < 25_000L;

        animateBattery(leftBatteryValue, leftBatteryBar, l);
        animateBattery(rightBatteryValue, rightBatteryBar, r);
        animateBattery(caseBatteryValue, caseBatteryBar, c > 0 && caseFresh ? c : -1);

        leftChargingState.setText(prefs.getBoolean(BudsConnectionService.PREF_CHARGING_LEFT, false) ? "Charging" : "");
        rightChargingState.setText(prefs.getBoolean(BudsConnectionService.PREF_CHARGING_RIGHT, false) ? "Charging" : "");
        caseChargingState.setText(prefs.getBoolean(BudsConnectionService.PREF_CHARGING_CASE, false) ? "Charging" : "");

        boolean subAcked = prefs.getBoolean(BudsConnectionService.PREF_SUBSCRIPTION_ACKED, false);
        boolean gotPush = prefs.getBoolean(BudsConnectionService.PREF_GOT_PUSH_UPDATE, false);
        debugStatusText.setText(
                "Subscription acknowledged: " + (subAcked ? "YES" : "no") + "\n" +
                "Received a pushed update: " + (gotPush ? "YES" : "no (relying on polling)"));

        String rawLog = prefs.getString(BudsConnectionService.PREF_LAST_RAW, "");
        debugText.setText(rawLog.isEmpty() ? "(no data received from buds yet)" : rawLog);
    }

    private void animateBattery(TextView value, android.widget.ProgressBar bar, int level) {
        int target = level > 0 ? Math.min(level, 100) : 0;
        value.setText(level > 0 ? level + "%" : "—");
        bar.setVisibility(level > 0 ? View.VISIBLE : View.INVISIBLE);
        int from = bar.getProgress();
        if (from == target) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, target);
        a.setDuration(350);
        a.addUpdateListener(v -> bar.setProgress((Integer) v.getAnimatedValue()));
        a.start();
    }

    private String resolveDeviceName(String mac) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return mac;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) {
                BluetoothDevice d = a.getRemoteDevice(mac);
                String n = d.getName();
                if (n != null && !n.trim().isEmpty()) return n + " (" + mac + ")";
            }
        } catch (Exception ignored) {}
        return mac;
    }

    /** Let the user pick from already-paired Bluetooth devices. */
    private void chooseDeviceDialog() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissionsThenStart();
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "No Bluetooth adapter on this device", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission needed", Toast.LENGTH_LONG).show();
            return;
        }

        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired devices found - pair your earbuds in system Bluetooth settings first", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            try {
                names[i] = devices.get(i).getName() + "  (" + devices.get(i).getAddress() + ")";
            } catch (SecurityException e) {
                names[i] = devices.get(i).getAddress();
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Select your earbuds")
                .setItems(names, (dialog, which) -> {
                    String mac = devices.get(which).getAddress();
                    getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(BudsConnectionService.PREF_MAC, mac)
                            .apply();
                    refreshStatus();
                    Toast.makeText(this, "Saved. Tap 'Start monitoring' next.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void requestPermissionsThenStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }

        startService();
    }

    private void startService() {
        SharedPreferences prefs = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        if (prefs.getString(BudsConnectionService.PREF_MAC, null) == null) {
            Toast.makeText(this, "Choose your earbuds first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, BudsConnectionService.class);
        ContextCompat.startForegroundService(this, svc);
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            startService();
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
