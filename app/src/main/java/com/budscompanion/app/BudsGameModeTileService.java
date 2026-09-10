package com.budscompanion.app;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings control for the app's Game/Low-latency mode state.
 * The current protocol source exposes the GAME_MODE subscription type but
 * does not contain a verified vendor command for changing it, so this tile
 * deliberately does not invent a wire command.
 */
public class BudsGameModeTileService extends TileService {
    @Override public void onStartListening() { super.onStartListening(); refreshTile(); }
    @Override public void onClick() {
        super.onClick();
        SharedPreferences p = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE);
        boolean enabled = !p.getBoolean(BudsConnectionService.PREF_GAME_MODE, false);
        p.edit().putBoolean(BudsConnectionService.PREF_GAME_MODE, enabled).apply();
        refreshTile();
    }
    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = getSharedPreferences(BudsConnectionService.PREFS, MODE_PRIVATE)
                .getBoolean(BudsConnectionService.PREF_GAME_MODE, false);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_headphones));
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Game mode");
        tile.setSubtitle(enabled ? "On" : "Off");
        tile.updateTile();
    }
}
