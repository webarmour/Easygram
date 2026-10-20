package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.Stories.recorder.HintView2;
import org.telegram.ui.Wallet.WalletActivity;

import java.util.ArrayList;
import java.util.Collections;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {

    public static final int INDEX_CHATS = 0;
    public static final int INDEX_CONTACTS = 1;
    public static final int INDEX_CALLS = 2;
    public static final int INDEX_SETTINGS = 3;
    public static final int INDEX_PROFILE = 4;

    private final ArrayList<Integer> activeTabs = new ArrayList<>();

    private void rebuildActiveTabs() {
        activeTabs.clear();
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        if (OtherSettingsActivity.isTabEnabled(INDEX_CHATS, prefs, currentAccount)) {
            activeTabs.add(INDEX_CHATS);
        }
        if (OtherSettingsActivity.isTabEnabled(INDEX_CONTACTS, prefs, currentAccount)) {
            activeTabs.add(INDEX_CONTACTS);
        }
        if (OtherSettingsActivity.isTabEnabled(INDEX_CALLS, prefs, currentAccount)) {
            activeTabs.add(INDEX_CALLS);
        }
        if (OtherSettingsActivity.isTabEnabled(INDEX_SETTINGS, prefs, currentAccount)) {
            activeTabs.add(INDEX_SETTINGS);
        }
        if (OtherSettingsActivity.isTabEnabled(INDEX_PROFILE, prefs, currentAccount)) {
            activeTabs.add(INDEX_PROFILE);
        }
        if (activeTabs.isEmpty()) {
            activeTabs.add(INDEX_CHATS);
        }
    }

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);

    private IUpdateLayout updateLayout;

    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private View fadeView;

    public MainTabsActivity() {
        super();
        rebuildActiveTabs();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceTabGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                hash.addF(fragmentPosition.left);
                                hash.addF(fragmentPosition.top);
                                hash.add(fragment.getClassGuid());
                            }
                        }
                    }
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    final int width = fragmentView.getMeasuredWidth();
                    final int height = fragmentView.getMeasuredHeight();

                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                canvas.save();
                                canvas.translate(fragmentPosition.left, fragmentPosition.top);
                                source.draw(canvas, 0, 0, width, height);
                                canvas.restore();
                            }
                        }
                    }
                }
            });
        } else {
            iBlur3SourceTabGlass = null;
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_fadeView();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
                blur3_updateFadeColors();
            }
        };
    }

    private int getEstBackgroundColor() {
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                viewPager != null ? viewPager.getPositionVisibility(0) : 1);
    }

    private boolean tabletLayout;
    public void updateLayout() {

    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    private void checkContactsTabBadge() {
        if (tabsView != null && tabs != null && tabs[INDEX_CONTACTS] != null) {
            final boolean hasPermission = Build.VERSION.SDK_INT >= 23 && ContactsController.hasContactsPermission();
            if (hasPermission) {
                MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts2", true).apply();
            }
            if (Build.VERSION.SDK_INT >= 23 && UserConfig.getInstance(currentAccount).syncContacts && !hasPermission && MessagesController.getGlobalNotificationsSettings().getBoolean("askAboutContacts2", true)) {
                tabs[INDEX_CONTACTS].setCounter("!", true, true);
            } else {
                tabs[INDEX_CONTACTS].setCounter(null, true, true);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    private void updateTabsVisibility(boolean animated) {
        rebuildActiveTabs();
        if (tabsView != null && tabs != null) {
            for (int index = 0; index < tabs.length; index++) {
                boolean visible = activeTabs.contains(index);
                tabsView.setViewVisible(tabs[index], visible, animated);
            }
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;
        rebuildActiveTabs();

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setClipChildren(false);
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        tabsView.setMaxWidth(dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));

        tabs = new GlassTabView[5];
        tabs[INDEX_CHATS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
        tabs[INDEX_CONTACTS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CONTACTS, R.string.MainTabsContacts);
        tabs[INDEX_CALLS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CALLS, R.string.MainTabsCalls);
        tabs[INDEX_SETTINGS] = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.SETTINGS, R.string.Settings);
        tabs[INDEX_PROFILE] = GlassTabView.createAvatar(context, resourceProvider, currentAccount, R.string.MainTabsProfile);

        tabs[INDEX_CHATS].setOnLongClickListener(this::openFoldersSelector);
        tabs[INDEX_CONTACTS].setOnLongClickListener(this::openContactsSelector);
        tabs[INDEX_CALLS].setOnLongClickListener(this::openCallsSelector);
        tabs[INDEX_PROFILE].setOnLongClickListener(this::openAccountSelector);

        for (int index = 0; index < tabs.length; index++) {
            tabsView.addTabToIgnoreClick(tabs[index]);
        }

        for (int index = 0; index < tabs.length; index++) {
            final int tabIndex = index;
            tabs[index].setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) {
                    return;
                }
                int position = activeTabs.indexOf(tabIndex);
                if (position == -1) return;

                if (viewPager.getCurrentPosition() == position) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) {
                        ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    }
                    return;
                }

                selectTab(position, true);
                viewPager.scrollToPosition(position);
            });

            tabsView.addView(tabs[index]);
        }
        updateTabsVisibility(false);

        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceTabGlass != null ? iBlur3SourceTabGlass : iBlur3SourceColor);
        iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
        iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        tabsViewBackground = iBlur3FactoryGlass.create(tabsView, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        tabsViewBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        tabsViewBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        tabsView.setBackground(tabsViewBackground);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);

        fadeView = new View(context);
        BlurredBackgroundWithFadeDrawable fadeDrawable = new BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeView, null));
        fadeDrawable.setFadeHeight(dp(60), true);
        fadeView.setBackground(fadeDrawable);

        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});
        tabsViewWrapper.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        tabsViewWrapper.setClipToPadding(false);
        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
        checkUnreadCount(false);
        return contentView;
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null || tabs == null || tabs[INDEX_CHATS] == null) {
            return;
        }

        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            tabs[INDEX_CHATS].setCounter(unreadCountFmt, false, animated);
        } else {
            tabs[INDEX_CHATS].setCounter(null, false, animated);
        }
    }

    public boolean openContactsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_contact_add, getString(R.string.NewContact), () -> {
            new NewContactBottomSheet(this, getContext()).show();
        });
        o.add(R.drawable.msg_calls, getString(R.string.VoipChatRecentCalls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            presentFragment(new CallLogActivity(args));
        });
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        boolean callsEnabled = OtherSettingsActivity.isTabEnabled(INDEX_CALLS, prefs, currentAccount);
        if (callsEnabled) {
            o.add(R.drawable.msg_archive_hide, getString(R.string.HideCallTab), () -> {
                prefs.edit().putBoolean(OtherSettingsActivity.KEY_TAB_CALLS, false).apply();
                getUserConfig().setShowCallsTab(false);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            });
        } else {
            o.add(R.drawable.menu_add_tab_24, getString(R.string.GroupCallShowInMainTabs), () -> {
                prefs.edit().putBoolean(OtherSettingsActivity.KEY_TAB_CALLS, true).apply();
                getUserConfig().setShowCallsTab(true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainTabsLayoutChanged);
            });
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private Integer pendingFolderId;

    private boolean openFoldersSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters == null || filters.size() <= 1) return false;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter folder = filters.get(i);
            final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            folderItem.setPadding(dp(18), 0, dp(18), 0);
            CharSequence title = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
            title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
            if (!folder.isDefault()) {
                title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
            }
            final int unreadCount = folder.isDefault()
                    ? MessagesStorage.getInstance(currentAccount).getMainUnreadCount()
                    : folder.unreadCount;
            if (unreadCount > 0) {
                final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                final int counterStart = titleWithCounter.length();
                titleWithCounter.append(String.valueOf(unreadCount));
                titleWithCounter.setSpan(
                        new FolderCounterSpan(unreadCount, hasUnmutedUnreadDialogs(folder)),
                        counterStart,
                        titleWithCounter.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                title = titleWithCounter;
                folderItem.setContentDescription(
                        TextUtils.concat(
                                folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name,
                                "\n",
                                LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                        )
                );
            }
            folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            final int filterId = folder.id;
            folderItem.setText(title);
            if (folder.isDefault()) {
                folderItem.setIcon(R.drawable.msg_folders);
            } else {
                final FolderDrawable drawable = new FolderDrawable(getParentActivity(), R.drawable.msg_folders, folder.color);
                folderItem.setIcon(drawable);
            }
            folderItem.setOnClickListener(v -> {
                if (dialogsActivity != null) {
                    dialogsActivity.switchToFolderId(filterId);
                } else {
                    pendingFolderId = filterId;
                }
                o.dismiss();
            });
            o.addView(folderItem);
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private boolean openAccountSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        for (int accountIndex = 0; accountIndex < UserConfig.MAX_ACCOUNT_COUNT; accountIndex++) {
            if (!UserConfig.getInstance(accountIndex).isClientActivated()) continue;
            final int acc = accountIndex;
            final ActionBarMenuSubItem accountItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            accountItem.setPadding(dp(18), 0, dp(18), 0);
            TLRPC.User u = UserConfig.getInstance(acc).getCurrentUser();
            accountItem.setText(UserObject.getUserName(u));
            AvatarDrawable avatarDrawable = new AvatarDrawable(u);
            avatarDrawable.setInfo(acc, u);
            accountItem.setIcon(avatarDrawable);
            if (acc == currentAccount) {
                accountItem.setSubtext(getString(R.string.Account));
            }
            accountItem.setOnClickListener(v -> {
                if (acc != currentAccount) {
                    LaunchActivity.instance.switchToAccount(acc, true);
                }
                o.dismiss();
            });
            o.addView(accountItem);
        }

        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_add, getString(R.string.AddAccount), () -> {
                int freeAccount = -1;
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        freeAccount = a;
                        break;
                    }
                }
                if (freeAccount >= 0) {
                    presentFragment(new LoginActivity(freeAccount));
                }
            });
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.RIGHT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private static class FolderCounterSpan extends ReplacementSpan {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String text;

        FolderCounterSpan(int count, boolean unmuted) {
            text = String.valueOf(count);
            backgroundPaint.setColor(Theme.getColor(unmuted ? Theme.key_chats_unreadCounter : Theme.key_chats_unreadCounterMuted));
            textPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
            textPaint.setTextSize(dp(11));
            textPaint.setTypeface(AndroidUtilities.bold());
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            final float textWidth = textPaint.measureText(this.text);
            return (int) (textWidth + dp(14));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float textWidth = textPaint.measureText(this.text);
            final float width = textWidth + dp(10);
            final float height = dp(16);
            final float centerY = (top + bottom) / 2f;
            final RectF rect = new RectF(x + dp(4), centerY - height / 2f, x + dp(4) + width, centerY + height / 2f);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, backgroundPaint);
            canvas.drawText(this.text, rect.centerX() - textWidth / 2f, centerY - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        }
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getDialogs(folder.id);
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog instanceof TLRPC.TL_dialog && ((TLRPC.TL_dialog) dialog).unread_count > 0 && !getMessagesController().isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        super.onViewPagerScrollEnd();
        selectTab(viewPager.getCurrentPosition(), true);
        final int currentPosition = viewPager.getCurrentPosition();
        if (pendingFolderId != null && activeTabs.contains(INDEX_CHATS) && currentPosition == activeTabs.indexOf(INDEX_CHATS) && dialogsActivity != null) {
            dialogsActivity.switchToFolderId(pendingFolderId);
            pendingFolderId = null;
        }
    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                selectTab(Math.round(position), true);
            }
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }

    @Override
    protected int getFragmentsCount() {
        if (activeTabs.isEmpty()) rebuildActiveTabs();
        return activeTabs.size();
    }

    @Override
    protected int getStartPosition() {
        int pos = activeTabs.indexOf(INDEX_CHATS);
        return pos != -1 ? pos : 0;
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putBoolean("hasMainTabs", true);
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        if (activeTabs.isEmpty()) rebuildActiveTabs();
        int pos = activeTabs.indexOf(INDEX_CHATS);
        if (pos != -1) {
            putFragmentAtPosition(pos, dialogsActivity);
        }
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        if (activeTabs.isEmpty()) rebuildActiveTabs();
        if (position < 0 || position >= activeTabs.size()) return null;
        int index = activeTabs.get(position);
        if (index == INDEX_CONTACTS) {
            Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", true);
            return new ContactsActivity(args);
        } else if (index == INDEX_CALLS) {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", true);
            return new CallLogActivity(args);
        } else if (index == INDEX_SETTINGS) {
            Bundle args = new Bundle();
            args.putBoolean("hasMainTabs", true);
            return new SettingsActivity(args);
        } else if (index == INDEX_CHATS) {
            Bundle args = new Bundle();
            args.putBoolean("hasMainTabs", true);
            dialogsActivity = new DialogsActivity(args);
            dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
            return dialogsActivity;
        } else if (index == INDEX_PROFILE) {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            args.putBoolean("my_profile", true);
            args.putBoolean("hasMainTabs", true);
            return new ProfileActivity(args);
        }
        return null;
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    public GlassTabView[] tabs;

    public void selectTab(int position, boolean animated) {
        if (tabs == null) return;
        for (int index = 0; index < tabs.length; index++) {
            GlassTabView tab = tabs[index];
            int pos = activeTabs.indexOf(index);
            tab.setSelected(pos == position, animated);
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        if (tabs == null) return;
        for (int index = 0; index < tabs.length; index++) {
            int pos = activeTabs.indexOf(index);
            float visibility = pos != -1 ? Math.max(0, 1f - Math.abs(pos - animatedPosition)) : 0f;
            tabs[index].setGestureSelectedOverride(visibility, allow);
        }
        tabsView.invalidate();
    }

    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default BlurredBackgroundSourceRenderNode getGlassSource() {
            return null;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            return delegate.canParentTabsSlide(ev, forward);

        }

        return false;
    }

    private int navigationBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = navigationBarHeight + updateLayoutHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            clearAllHiddenFragments();
        } else if (id == NotificationCenter.mainTabsLayoutChanged || id == NotificationCenter.callTabsVisibleToggled) {
            updateTabsVisibility(true);
            if (viewPager != null) {
                int currentPos = viewPager.getCurrentPosition();
                if (currentPos >= activeTabs.size()) {
                    currentPos = getStartPosition();
                    viewPager.scrollToPosition(currentPos);
                }
                fragmentsArr.clear();
                viewPager.rebuild(false);
                selectTab(viewPager.getCurrentPosition(), false);
            }
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            if (tabs != null && tabs[INDEX_PROFILE] != null) {
                tabs[INDEX_PROFILE].updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.contactsPermissionBadgeCheck) {
            checkContactsTabBadge();
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;

    @Override
    public boolean onFragmentCreate() {
        observersGroup = NotificationCenter.getInstance(currentAccount)
            .createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.callTabsVisibleToggled)
            .add(NotificationCenter.mainTabsLayoutChanged)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck)
            .addGlobal(NotificationCenter.appUpdateAvailable)
            .addGlobal(NotificationCenter.appUpdateLoading)
            .addGlobal(NotificationCenter.needSetDayNightTheme);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }

        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE) {
            checkUi_tabsPosition();
            checkUi_fadeView();
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }

        final float animatedPosition = viewPager.getPositionAnimated();
        int profilePos = activeTabs.indexOf(INDEX_PROFILE);
        final float isProfile = profilePos != -1 ? 1f - MathUtils.clamp(Math.abs(profilePos - animatedPosition), 0, 1) : 0f;
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue();
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_tabsPosition() {
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float factor = animatorTabsVisible.getFloatValue();

        tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, factor));
        tabsView.setClickable(factor > 1);
        tabsView.setEnabled(factor > 1);
        tabsView.setAlpha(factor);
        tabsView.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_dialogBackground));

        return themeDescriptions;
    }

    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            animatorTabsVisible.setValue(visible, true);
        }
    }

    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }

    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;

    private void showAccountChangeHint() {
        if (accountSwitchHintShown) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null || tabs == null) return;
                if (!activeTabs.contains(INDEX_PROFILE)) return;

                final View v = tabs[INDEX_PROFILE];
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                accountSwitchHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint.setTranslationY(-navigationBarHeight + dp(4));
                accountSwitchHint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                accountSwitchHint.setMultilineText(false);
                accountSwitchHint.setCloseButton(true);
                accountSwitchHint.setText(getString(R.string.SwitchAccountHint));
                accountSwitchHint.setJoint(1, -translate + 7.33f);
                contentView.addView(accountSwitchHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                accountSwitchHint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(accountSwitchHint));
                accountSwitchHint.setDuration(8000);
                accountSwitchHint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            }, 1500);
        }

        accountSwitchHintShown = true;
    }

    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;

    private final RectF fragmentPosition = new RectF();
    private void blur3_invalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || iBlur3SourceTabGlass == null || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        iBlur3SourceTabGlass.setSize(width, height);
        iBlur3SourceTabGlass.updateDisplayListIfNeeded();
    }

    private void blur3_updateFadeColors() {
        iBlur3SourceColor.setColor(getEstBackgroundColor());
        if (fadeView != null) {
            fadeView.invalidate();
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.updateColors();
        }
        blur3_invalidateBlur();
        if (fadeView != null) {
            fadeView.invalidate();
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                tabView.updateColorsLottie();
            }
        }
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }
}
