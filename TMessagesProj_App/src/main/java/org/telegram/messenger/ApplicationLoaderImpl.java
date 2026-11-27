package org.telegram.messenger;

import android.app.Activity;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
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
        return EasygramChannel.takeUpdateLayout(activity, sideMenuContainer);
    }

    @Override
    protected void onCheckEasygramUpdate(Activity activity, boolean manual) {
        EasygramChannel.checkUpdate(activity, manual);
    }

    @Override
    protected void onOpenEasygramSupport(org.telegram.ui.ActionBar.BaseFragment fragment, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourceProvider) {
        EasygramChannel.openSupport(fragment, resourceProvider);
    }
}
