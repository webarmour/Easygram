package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Easygram settings for optional app integrations. */
public class OtherSettingsActivity extends BaseFragment {
    public static final String DEFAULT_GALLERY_PACKAGE = "easygram_default_gallery_package";
    public static final String EMBEDDED_PHOTO_PICKER = "__embedded_photo_picker__";

    private TextSettingsCell galleryCell;

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    private String getSelectedGalleryLabel() {
        String packageName = preferences().getString(DEFAULT_GALLERY_PACKAGE, null);
        if (packageName == null) {
            return LocaleController.getString(R.string.EasygramBuiltInGallery);
        }
        if (EMBEDDED_PHOTO_PICKER.equals(packageName)) {
            return LocaleController.getString(R.string.EasygramEmbeddedPhotoPicker);
        }
        try {
            return getParentActivity().getPackageManager()
                    .getApplicationLabel(getParentActivity().getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Exception ignored) {
            return LocaleController.getString(R.string.EasygramBuiltInGallery);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EasygramOther));
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell header = new HeaderCell(context);
        header.setText(LocaleController.getString(R.string.EasygramOther));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        galleryCell = new TextSettingsCell(context);
        galleryCell.setTextAndValue(
                LocaleController.getString(R.string.EasygramDefaultGallery),
                getSelectedGalleryLabel(),
                false
        );
        galleryCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        galleryCell.setOnClickListener(this::showGalleryChooser);
        content.addView(galleryCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.EasygramDefaultGalleryInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = root;
        return fragmentView;
    }

    private void showGalleryChooser(View view) {
        if (getParentActivity() == null) return;
        PackageManager packageManager = getParentActivity().getPackageManager();
        Map<String, ResolveInfo> uniqueApps = new LinkedHashMap<>();
        String[] mediaTypes = {"image/*", "video/*", "*/*"};
        for (String mediaType : mediaTypes) {
            Intent queryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            queryIntent.setType(mediaType);
            List<ResolveInfo> resolved = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : resolved) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    uniqueApps.putIfAbsent(info.activityInfo.packageName, info);
                }
            }
        }
        ArrayList<ResolveInfo> apps = new ArrayList<>(uniqueApps.values());
        Collections.sort(apps, Comparator.comparing(
                info -> info.loadLabel(packageManager).toString(), String.CASE_INSENSITIVE_ORDER
        ));

        String[] labels = new String[apps.size() + 2];
        labels[0] = LocaleController.getString(R.string.EasygramBuiltInGallery);
        labels[1] = LocaleController.getString(R.string.EasygramEmbeddedPhotoPicker);
        for (int i = 0; i < apps.size(); i++) labels[i + 2] = apps.get(i).loadLabel(packageManager).toString();

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.EasygramDefaultGallery));
        builder.setItems(labels, (dialog, index) -> {
            SharedPreferences.Editor editor = preferences().edit();
            if (index == 0) {
                editor.remove(DEFAULT_GALLERY_PACKAGE);
            } else if (index == 1) {
                editor.putString(DEFAULT_GALLERY_PACKAGE, EMBEDDED_PHOTO_PICKER);
            } else {
                editor.putString(DEFAULT_GALLERY_PACKAGE, apps.get(index - 2).activityInfo.packageName);
            }
            editor.apply();
            if (galleryCell != null) {
                galleryCell.setTextAndValue(
                        LocaleController.getString(R.string.EasygramDefaultGallery),
                        getSelectedGalleryLabel(),
                        false
                );
            }
        });
        builder.show();
    }
}
