package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import io.noties.markwon.Markwon;

/** Release notes shown before downloading an Easygram update. */
final class EasygramUpdateSheet extends BottomSheet {
    EasygramUpdateSheet(Context context, String version, String notes, boolean downloaded, Runnable update) {
        super(context, false);
        setCanDismissWithSwipe(false);

        boolean isDark = Theme.isCurrentThemeDark();
        int bgColor = context.getColor(isDark
                ? R.color.easygram_bloom_mono_background
                : R.color.easygram_bloom_blue_dark_background);
        setBackgroundColor(bgColor);
        fixNavigationBar(bgColor);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Keep both actions visible even with long GitHub release notes.
                int maxHeight = AndroidUtilities.displaySize.y / 2;
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    maxHeight = Math.min(maxHeight, MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(AndroidUtilities.dp(23), 0, AndroidUtilities.dp(23), AndroidUtilities.dp(16));
        scroll.addView(details);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView logo = new ImageView(context);
        logo.setImageResource(isDark
                ? R.mipmap.ic_easygram_bloom_mono_round
                : R.mipmap.ic_easygram_bloom_blue_dark_round);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        details.addView(logo, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 12));

        TextView title = text(context, 20, 0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(context.getString(R.string.AppName) + " " + version);
        details.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView changelog = text(context, 14, 0xFFFFFFFF);
        changelog.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink));
        changelog.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        String body = TextUtils.isEmpty(notes) || notes.trim().isEmpty()
                ? context.getString(R.string.EasygramReleaseNotesEmpty) : notes;
        // Reuse the project's Markdown renderer: headings, lists, emphasis and links.
        Markwon.create(context).setMarkdown(changelog, body);
        details.addView(changelog, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        TextView download = text(context, 14, Theme.getColor(Theme.key_featuredStickers_buttonText));
        download.setTypeface(AndroidUtilities.bold());
        download.setGravity(Gravity.CENTER);
        download.setText(LocaleController.getString(downloaded ? R.string.AppUpdateNow : R.string.AppUpdateDownloadNow));
        download.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
        content.addView(download, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 12, 16, 0));
        download.setOnClickListener(v -> {
            dismiss();
            update.run();
        });

        TextView later = text(context, 14, Theme.getColor(Theme.key_dialogTextLink));
        later.setGravity(Gravity.CENTER);
        later.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater));
        content.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        later.setOnClickListener(v -> dismiss());
        setCustomView(content);
    }

    private static TextView text(Context context, int size, int textColor) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(textColor);
        return view;
    }
}
