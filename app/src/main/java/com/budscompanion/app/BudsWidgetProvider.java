package com.budscompanion.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

public class BudsWidgetProvider extends AppWidgetProvider {

    // Keep in sync with BudsConnectionService.CASE_STALE_MS
    private static final long CASE_STALE_MS = 25_000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    public static void updateAllWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, BudsWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(provider);
        for (int id : ids) {
            updateWidget(context, mgr, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager mgr, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(BudsConnectionService.PREFS, Context.MODE_PRIVATE);
        boolean connected = prefs.getBoolean(BudsConnectionService.PREF_CONNECTED, false);
        int left = connected ? prefs.getInt(BudsConnectionService.PREF_LEFT, -1) : -1;
        int right = connected ? prefs.getInt(BudsConnectionService.PREF_RIGHT, -1) : -1;
        int caseLevel = connected ? prefs.getInt(BudsConnectionService.PREF_CASE, -1) : -1;
        long caseTs = prefs.getLong(BudsConnectionService.PREF_CASE_TS, 0);
        boolean caseFresh = (System.currentTimeMillis() - caseTs) < CASE_STALE_MS;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_buds);

        if (left > 0) {
            views.setViewVisibility(R.id.widget_left_group, View.VISIBLE);
            views.setTextViewText(R.id.widget_left, left + "%");
        } else {
            views.setViewVisibility(R.id.widget_left_group, View.GONE);
        }

        if (right > 0) {
            views.setViewVisibility(R.id.widget_right_group, View.VISIBLE);
            views.setTextViewText(R.id.widget_right, right + "%");
        } else {
            views.setViewVisibility(R.id.widget_right_group, View.GONE);
        }

        if (caseLevel > 0 && caseFresh) {
            views.setViewVisibility(R.id.widget_case_group, View.VISIBLE);
            views.setTextViewText(R.id.widget_case, caseLevel + "%");
        } else {
            views.setViewVisibility(R.id.widget_case_group, View.GONE);
        }

        mgr.updateAppWidget(widgetId, views);
    }
}
