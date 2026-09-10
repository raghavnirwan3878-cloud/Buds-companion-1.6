package com.budscompanion.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.core.content.ContextCompat;

public class BudsTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        SharedPreferences prefs = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        boolean currentlyEnabled = prefs.getBoolean(BudsConnectionService.PREF_MONITORING_ENABLED, true);
        boolean turningOn = !currentlyEnabled;

        prefs.edit().putBoolean(BudsConnectionService.PREF_MONITORING_ENABLED, turningOn).apply();

        if (turningOn) {
            String mac = prefs.getString(BudsConnectionService.PREF_MAC, null);
            if (mac != null) {
                ContextCompat.startForegroundService(this, new Intent(this, BudsConnectionService.class));
            }
        } else {
            stopService(new Intent(this, BudsConnectionService.class));
        }

        // Reflect the tap immediately rather than waiting for the connection
        // attempt to resolve - refreshTile() below will correct the label
        // with real battery numbers once the service reports back.
        refreshTile();
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        SharedPreferences prefs = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        boolean monitoringEnabled = prefs.getBoolean(BudsConnectionService.PREF_MONITORING_ENABLED, true);
        boolean connected = prefs.getBoolean(BudsConnectionService.PREF_CONNECTED, false);
        int left = prefs.getInt(BudsConnectionService.PREF_LEFT, -1);
        int right = prefs.getInt(BudsConnectionService.PREF_RIGHT, -1);

        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_headphones));

        if (!monitoringEnabled) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Devices");
            tile.setSubtitle("Off");
        } else if (!connected) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Devices");
            tile.setSubtitle("Connecting\u2026");
        } else {
            tile.setState(Tile.STATE_ACTIVE);
            StringBuilder label = new StringBuilder();
            if (left > 0) label.append("L").append(left).append("%");
            if (right > 0) {
                if (label.length() > 0) label.append(" ");
                label.append("R").append(right).append("%");
            }
            tile.setLabel(label.length() > 0 ? label.toString() : "Devices");
            tile.setSubtitle("On");
        }
        tile.updateTile();
    }
}

