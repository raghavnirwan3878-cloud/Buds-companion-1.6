package com.budscompanion.app;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class BudsBatteryTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); refreshTile(); }
    @Override public void onClick() { super.onClick(); refreshTile(); }
    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        boolean connected = p.getBoolean(BudsConnectionService.PREF_CONNECTED, false);
        int l = p.getInt(BudsConnectionService.PREF_LEFT, -1);
        int r = p.getInt(BudsConnectionService.PREF_RIGHT, -1);
        int c = p.getInt(BudsConnectionService.PREF_CASE, -1);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_headphones));
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Battery");
        String text = (l > 0 ? "L " + l + "%" : "L —") + "  " + (r > 0 ? "R " + r + "%" : "R —");
        if (c > 0) text += "  C " + c + "%";
        tile.setSubtitle(text);
        tile.updateTile();
    }
}
