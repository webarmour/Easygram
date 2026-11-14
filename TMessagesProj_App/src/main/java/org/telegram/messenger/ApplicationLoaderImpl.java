package org.telegram.messenger;

import android.app.Activity;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.Components.EasygramUpdater;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    {
        // runs before Application.onCreate: the App Bundle flavor is the Google Play build
        EasygramBuild.playStore = BuildConfig.BUNDLE;
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return EasygramBuild.isPlayStore() ? null : EasygramUpdater.attach(activity, sideMenuContainer);
    }
}
