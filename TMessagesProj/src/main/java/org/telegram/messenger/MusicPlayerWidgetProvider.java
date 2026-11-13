package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Home-screen music player widget (Easygram). Rendering and live updates: {@link MusicWidgetUpdater}. */
public class MusicPlayerWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        ApplicationLoader.postInitApplication();
        MusicWidgetUpdater.getInstance().start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        // resized: re-render the frames for the new width
        MusicWidgetUpdater.getInstance().start();
    }

    @Override
    public void onEnabled(Context context) {
        ApplicationLoader.postInitApplication();
        MusicWidgetUpdater.getInstance().start();
    }
}
