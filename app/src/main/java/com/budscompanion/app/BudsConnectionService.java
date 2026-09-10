package com.budscompanion.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Holds a persistent classic-Bluetooth (RFCOMM/SPP) connection to the
 * Realme/Oppo earbuds, subscribes to push battery updates, and falls back to
 * periodic polling. Updates the widget, Quick Settings tile, and fires
 * low-battery notifications.
 *
 * Started manually - via the app's "Start monitoring" button or the Quick
 * Settings tile - not automatically on Bluetooth connect. (An earlier
 * version auto-started via the ACL_CONNECTED broadcast; that was reverted
 * because it interfered with the phone's own Bluetooth profile negotiation
 * for the earbuds, causing the system's own battery indicator and the
 * earbuds' mic to stop working correctly.)
 *
 * Talks the vendor protocol directly (reverse-engineered from Gadgetbridge's
 * AGPLv3-licensed OppoHeadphonesProtocol/OppoHeadphonesSupport), bypassing
 * Gadgetbridge/realme Link entirely.
 */
public class BudsConnectionService extends Service {

    private static final String TAG = "BudsConnection";

    // Same SPP service UUID Gadgetbridge's OppoHeadphonesSupport registers.
    private static final UUID SPP_UUID = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");

    private static final String CHANNEL_STATUS = "buds_status";
    private static final String CHANNEL_ALERTS = "buds_alerts";
    private static final int NOTIF_ID_STATUS = 1;
    private static final int NOTIF_ID_LOW_BATTERY_BASE = 100;

    public static final String PREFS = "buds_prefs";
    public static final String PREF_MAC = "device_mac";
    public static final String PREF_LEFT = "level_left";
    public static final String PREF_RIGHT = "level_right";
    public static final String PREF_CASE = "level_case";
    public static final String PREF_CASE_TS = "level_case_ts";
    public static final String PREF_LOW_THRESHOLD = "low_threshold";
    public static final String PREF_LAST_RAW = "last_raw_frames";
    public static final String PREF_CONNECTED = "is_connected";
    public static final String PREF_SUBSCRIPTION_ACKED = "subscription_acked";
    public static final String PREF_GOT_PUSH_UPDATE = "got_push_update";
    public static final String PREF_MONITORING_ENABLED = "monitoring_enabled";
    public static final String ACTION_LEVELS_UPDATED = "com.budscompanion.app.LEVELS_UPDATED";
    public static final String ACTION_REFRESH_BATTERY = "com.budscompanion.app.REFRESH_BATTERY";
    public static final String PREF_CHARGING_LEFT = "charging_left";
    public static final String PREF_CHARGING_RIGHT = "charging_right";
    public static final String PREF_CHARGING_CASE = "charging_case";

    // Case battery is only considered "current" if we heard about it within
    // this window; older than that, we treat the case as closed/out of range
    // and hide it rather than show a stale number.
    private static final long CASE_STALE_MS = 25_000L;

    // Fallback poll interval - matters a lot if the earbuds don't actually
    // honor our push-subscription request.
    private static final long POLL_INTERVAL_MS = 10 * 1000L; // 10 seconds
    // Extra rapid retries right after connecting, for the very first reading.
    private static final long[] BURST_RETRY_DELAYS_MS = {1500, 3000, 5000, 8000};

    private static final long RECONNECT_BASE_DELAY_MS = 5_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OppoProtocol protocol = new OppoProtocol();
    private final OppoProtocol.FrameReader frameReader = new OppoProtocol.FrameReader();

    private BluetoothSocket socket;
    private OutputStream outStream;
    private volatile boolean running = false;
    private volatile boolean gotFirstReading = false;
    private long reconnectDelay = RECONNECT_BASE_DELAY_MS;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            sendBatteryRequest();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID_STATUS, buildStatusNotification("Starting\u2026"));
        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_MONITORING_ENABLED, true)
                .apply();
        connectLoop();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        markDisconnected();
        closeSocketQuietly();
        super.onDestroy();
    }

    // ---- Connection handling -------------------------------------------------

    private void connectLoop() {
        new Thread(() -> {
            while (running) {
                try {
                    attemptConnect();
                    reconnectDelay = RECONNECT_BASE_DELAY_MS; // reset backoff on success
                    readLoop(); // blocks until disconnect/error
                } catch (SecurityException se) {
                    Log.e(TAG, "Missing Bluetooth permission", se);
                    return; // nothing more we can do without the permission
                } catch (Exception e) {
                    Log.w(TAG, "Connection attempt failed: " + e.getMessage());
                }

                markDisconnected();
                closeSocketQuietly();

                if (!running) {
                    return;
                }
                try {
                    Thread.sleep(reconnectDelay);
                } catch (InterruptedException ignored) {
                }
                reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY_MS);
                // Deliberately not touching the notification here - we only
                // ever show "connected" states, never a reconnect/failure spam.
            }
        }, "buds-connect-loop").start();
    }

    private void attemptConnect() throws IOException {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mac = prefs.getString(PREF_MAC, null);
        if (mac == null) {
            throw new IOException("No paired device configured yet");
        }
        if (!hasBtPermission()) {
            throw new SecurityException("BLUETOOTH_CONNECT not granted");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IOException("No Bluetooth adapter");
        }
        BluetoothDevice device = adapter.getRemoteDevice(mac);
        adapter.cancelDiscovery();

        // Many OEM earbuds reject Android's default *secure* (authenticated+
        // encrypted) RFCOMM socket. Try insecure first; fall back to secure.
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
        } catch (IOException e) {
            closeSocketQuietly();
            Log.w(TAG, "Insecure socket failed (" + e.getMessage() + "), trying secure");
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
        }
        outStream = socket.getOutputStream();

        Log.i(TAG, "Connected to " + mac);
        gotFirstReading = false;
        prefs.edit()
                .putBoolean(PREF_CONNECTED, true)
                .putBoolean(PREF_SUBSCRIPTION_ACKED, false)
                .putBoolean(PREF_GOT_PUSH_UPDATE, false)
                .apply();
        handler.post(() -> {
            updateStatusNotification("Connected");
            BudsWidgetProvider.updateAllWidgets(this);
            sendBroadcast(new Intent(ACTION_LEVELS_UPDATED));
            requestTileRefresh();
        });

        // Subscribe to push battery updates, then request an immediate read.
        write(protocol.encodeSubscriptionSet(OppoProtocol.SUB_BATTERY));
        write(protocol.encodeBatteryReq());

        handler.removeCallbacks(pollRunnable);
        scheduleBurstRetries(0);
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /** Rapid re-requests right after connecting, so the first reading shows up quickly. */
    private void scheduleBurstRetries(int index) {
        if (index >= BURST_RETRY_DELAYS_MS.length) {
            return;
        }
        handler.postDelayed(() -> {
            if (!running || gotFirstReading) {
                return;
            }
            sendBatteryRequest();
            scheduleBurstRetries(index + 1);
        }, BURST_RETRY_DELAYS_MS[index]);
    }

    private void readLoop() throws IOException {
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[512];
        while (running) {
            int n = in.read(buf);
            if (n < 0) {
                throw new IOException("Stream closed by remote");
            }
            if (n == 0) {
                continue;
            }
            List<byte[]> frames = frameReader.feed(buf, n);
            if (frames.isEmpty()) {
                logRawFrame(java.util.Arrays.copyOf(buf, n));
            }
            for (byte[] frame : frames) {
                handleFrame(frame);
            }
        }
    }

    private void handleFrame(byte[] frame) {
        logRawFrame(frame);
        OppoProtocol.Decoded decoded = OppoProtocol.decodeFrame(frame);
        if (decoded == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (decoded.isSubscriptionAck) {
            prefs.edit().putBoolean(PREF_SUBSCRIPTION_ACKED, true).apply();
            handler.post(() -> sendBroadcast(new Intent(ACTION_LEVELS_UPDATED)));
        }
        if (decoded.isPushedUpdate) {
            prefs.edit().putBoolean(PREF_GOT_PUSH_UPDATE, true).apply();
        }
        if (decoded.batteries.isEmpty()) {
            return;
        }
        gotFirstReading = true;
        applyBatteryUpdate(decoded.batteries);
    }

    private void sendBatteryRequest() {
        try {
            write(protocol.encodeBatteryReq());
        } catch (IOException e) {
            Log.w(TAG, "Failed to send poll request: " + e.getMessage());
        }
    }

    private synchronized void write(byte[] data) throws IOException {
        if (outStream == null) {
            throw new IOException("Not connected");
        }
        outStream.write(data);
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        outStream = null;
    }

    /** Nudge the Quick Settings tile (if added) to refresh with latest data. */
    private void requestTileRefresh() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, BudsTileService.class));
        } catch (Exception e) {
            Log.w(TAG, "Tile refresh request failed: " + e.getMessage());
        }
    }

    private void markDisconnected() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean(PREF_CONNECTED, false)
                // Clear stale readings outright - a percentage from before a
                // disconnect is not "current" and shouldn't linger on screen.
                .putInt(PREF_LEFT, -1)
                .putInt(PREF_RIGHT, -1)
                .putInt(PREF_CASE, -1)
                .apply();
        handler.post(() -> {
            BudsWidgetProvider.updateAllWidgets(this);
            sendBroadcast(new Intent(ACTION_LEVELS_UPDATED));
            requestTileRefresh();
        });
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ---- Raw debug capture -------------------------------------------------

    /** Keep the last few raw frames (as hex) visible in the app for on-device debugging. */
    private void logRawFrame(byte[] frame) {
        StringBuilder hex = new StringBuilder();
        for (byte b : frame) {
            hex.append(String.format("%02X ", b));
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String prev = prefs.getString(PREF_LAST_RAW, "");
        String line = hex.toString().trim();
        String combined = line + "\n" + prev;
        String[] lines = combined.split("\n");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 8); i++) {
            kept.append(lines[i]).append("\n");
        }
        prefs.edit().putString(PREF_LAST_RAW, kept.toString()).apply();
    }

    // ---- Battery state / widget / notifications -------------------------------------------------

    private void applyBatteryUpdate(List<OppoProtocol.BatteryInfo> batteries) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        int threshold = prefs.getInt(PREF_LOW_THRESHOLD, 20);
        long now = System.currentTimeMillis();

        for (OppoProtocol.BatteryInfo b : batteries) {
            if (b.index == 0) editor.putBoolean(PREF_CHARGING_LEFT, b.charging);
            else if (b.index == 1) editor.putBoolean(PREF_CHARGING_RIGHT, b.charging);
            else if (b.index == 2) editor.putBoolean(PREF_CHARGING_CASE, b.charging);

            if (b.index == 2) {
                // Case: a 0% reading here means the device is actively
                // saying "not present" (closed/away) - store it as -1 so
                // it hides immediately rather than waiting on staleness.
                editor.putInt(PREF_CASE, b.level > 0 ? b.level : -1);
                editor.putLong(PREF_CASE_TS, now);
                continue;
            }

            String key = b.index == 0 ? PREF_LEFT : PREF_RIGHT;
            // Treat a reported 0% earbud as "not currently present" (out of
            // case / disconnected) rather than a real reading, and hide it.
            int storeLevel = b.level > 0 ? b.level : -1;
            int previous = prefs.getInt(key, -1);
            editor.putInt(key, storeLevel);

            if (!b.charging && b.level > 0 && previous > threshold && b.level <= threshold) {
                fireLowBatteryNotification(b);
            }
        }
        editor.apply();

        handler.post(() -> {
            updateStatusNotification(summaryText(getSharedPreferences(PREFS, MODE_PRIVATE)));
            BudsWidgetProvider.updateAllWidgets(this);
            sendBroadcast(new Intent(ACTION_LEVELS_UPDATED));
            requestTileRefresh();
        });
    }

    private String summaryText(SharedPreferences prefs) {
        int l = prefs.getInt(PREF_LEFT, -1);
        int r = prefs.getInt(PREF_RIGHT, -1);
        int c = prefs.getInt(PREF_CASE, -1);
        long caseTs = prefs.getLong(PREF_CASE_TS, 0);
        boolean caseFresh = (System.currentTimeMillis() - caseTs) < CASE_STALE_MS;

        StringBuilder sb = new StringBuilder();
        if (l > 0) sb.append("L ").append(l).append("%  ");
        if (r > 0) sb.append("R ").append(r).append("%  ");
        if (c > 0 && caseFresh) sb.append("Case ").append(c).append("%");
        return sb.length() > 0 ? sb.toString().trim() : "Connected";
    }

    // ---- Notifications -------------------------------------------------

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "Low battery alerts", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(alerts);
    }

    private Notification buildStatusNotification(String text) {
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setContentTitle("Devices")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_headphones)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build();
    }

    private void updateStatusNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID_STATUS, buildStatusNotification(text));
        }
    }

    private void fireLowBatteryNotification(OppoProtocol.BatteryInfo b) {
        String slot = b.index == 0 ? "Left earbud" : b.index == 1 ? "Right earbud" : "Case";
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setContentTitle(slot + " battery low")
                .setContentText(slot + " is at " + b.level + "%")
                .setSmallIcon(R.drawable.ic_headphones)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID_LOW_BATTERY_BASE + b.index, n);
        }
    }
}
