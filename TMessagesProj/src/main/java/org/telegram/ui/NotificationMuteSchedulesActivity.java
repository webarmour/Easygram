/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationMuteScheduleController;
import org.telegram.messenger.NotificationMuteScheduleController.DialogSchedule;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class NotificationMuteSchedulesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ADD_CHATS = -1;

    private UniversalRecyclerView listView;
    private ArrayList<DialogSchedule> schedules = new ArrayList<>();

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addWeakObserver(this, NotificationCenter.notificationsSettingsUpdated);
        reloadSchedules();
        loadMissingPeers();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.notificationsSettingsUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadSchedules();
        loadMissingPeers();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsSettingsUpdated) {
            reloadSchedules();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void reloadSchedules() {
        schedules = NotificationMuteScheduleController.getInstance(currentAccount).getAllSchedules();
    }

    private void loadMissingPeers() {
        ArrayList<Long> missingChats = new ArrayList<>();
        ArrayList<Long> missingUsers = new ArrayList<>();
        for (int i = 0; i < schedules.size(); i++) {
            long did = schedules.get(i).dialogId;
            if (did < 0) {
                if (getMessagesController().getChat(-did) == null) {
                    missingChats.add(-did);
                }
            } else if (did > 0) {
                if (getMessagesController().getUser(did) == null) {
                    missingUsers.add(did);
                }
            }
        }
        if (missingChats.isEmpty() && missingUsers.isEmpty()) {
            return;
        }
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            boolean loaded = false;
            for (int i = 0; i < missingChats.size(); i++) {
                TLRPC.Chat chat = getMessagesStorage().getChat(missingChats.get(i));
                if (chat != null) {
                    getMessagesController().putChat(chat, true);
                    loaded = true;
                }
            }
            for (int i = 0; i < missingUsers.size(); i++) {
                TLRPC.User user = getMessagesStorage().getUser(missingUsers.get(i));
                if (user != null) {
                    getMessagesController().putUser(user, true);
                    loaded = true;
                }
            }
            if (loaded) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
            }
        });
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(getString(R.string.NotificationMuteScheduleTitle));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setSectionsDrawBackground(true);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_ADD_CHATS, R.drawable.menu_premium_clock_add, getString(R.string.NotificationMuteScheduleAddChats)).accent());
        if (!schedules.isEmpty()) {
            items.add(UItem.asShadow(-100, null));
            items.add(UItem.asHeader(getString(R.string.NotificationMuteScheduleTitle)));
            for (int i = 0; i < schedules.size(); i++) {
                DialogSchedule schedule = schedules.get(i);
                items.add(DialogScheduleCell.Factory.of(i, schedule));
            }
        }
        items.add(UItem.asShadow(-101, getString(R.string.NotificationMuteScheduleListInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD_CHATS) {
            UsersSelectActivity selectActivity = new UsersSelectActivity(true, new ArrayList<>(), 0);
            selectActivity.noChatTypes = true;
            selectActivity.allowBots = true;
            selectActivity.allowSelf = false;
            selectActivity.customTitle = getString(R.string.NotificationMuteScheduleSelectChats);
            selectActivity.setDelegate((ids, flags) -> {
                if (ids == null || ids.isEmpty()) {
                    return;
                }
                if (ids.size() == 1) {
                    presentFragment(new NotificationMuteScheduleActivity(ids.get(0), 0, getResourceProvider()));
                } else {
                    presentFragment(new NotificationMuteScheduleActivity(ids, getResourceProvider()));
                }
            });
            presentFragment(selectActivity);
            return;
        }

        if (item.object instanceof DialogSchedule) {
            DialogSchedule schedule = (DialogSchedule) item.object;
            boolean switchClick = LocaleController.isRTL ? x <= dp(76) : x >= view.getMeasuredWidth() - dp(76);
            if (switchClick) {
                boolean newEnabled = !schedule.enabled;
                NotificationMuteScheduleController.getInstance(currentAccount)
                        .setScheduleEnabled(schedule.dialogId, schedule.topicId, newEnabled);
                reloadSchedules();
                if (view instanceof DialogScheduleCell) {
                    ((DialogScheduleCell) view).setChecked(newEnabled, true);
                }
                listView.adapter.update(true);
            } else {
                presentFragment(new NotificationMuteScheduleActivity(schedule.dialogId, schedule.topicId, getResourceProvider()));
            }
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof DialogSchedule) || getParentActivity() == null) {
            return false;
        }
        DialogSchedule schedule = (DialogSchedule) item.object;
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.NotificationMuteScheduleDeleteConfirmTitle))
                .setMessage(getString(R.string.NotificationMuteScheduleDeleteConfirm))
                .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                    NotificationMuteScheduleController.getInstance(currentAccount)
                            .removeSchedule(schedule.dialogId, schedule.topicId);
                    reloadSchedules();
                    listView.adapter.update(true);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            ThemeBind.textColor(button, Theme.key_text_RedBold, getResourceProvider());
        }
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    public static class DialogScheduleCell extends FrameLayout {

        private final BackupImageView avatarImageView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleTextView;
        private final TextView subtitleTextView;
        private final Switch checkBox;
        private final Theme.ResourcesProvider resourcesProvider;
        private boolean needDivider;

        public DialogScheduleCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setWillNotDraw(false);

            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(dp(20));
            addView(avatarImageView, LayoutHelper.createFrame(40, 40,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                    LocaleController.isRTL ? 0 : 16, 0, LocaleController.isRTL ? 16 : 0, 0));

            titleTextView = new TextView(context);
            ThemeBind.textColor(titleTextView, Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleTextView.setLines(1);
            titleTextView.setMaxLines(1);
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            titleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                    LocaleController.isRTL ? 80 : 68, 11, LocaleController.isRTL ? 68 : 80, 0));

            subtitleTextView = new TextView(context);
            ThemeBind.textColor(subtitleTextView, Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleTextView.setLines(1);
            subtitleTextView.setMaxLines(1);
            subtitleTextView.setSingleLine(true);
            subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(subtitleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                    LocaleController.isRTL ? 80 : 68, 35, LocaleController.isRTL ? 68 : 80, 0));

            checkBox = new Switch(context, resourcesProvider);
            checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            checkBox.setFocusable(false);
            addView(checkBox, LayoutHelper.createFrame(37, 40,
                    (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                    21, 0, 21, 0));
        }

        public void setChecked(boolean checked, boolean animated) {
            checkBox.setChecked(checked, animated);
        }

        public void bind(int currentAccount, DialogSchedule schedule, boolean divider) {
            this.needDivider = divider;
            MessagesController mc = MessagesController.getInstance(currentAccount);
            CharSequence title;
            if (schedule.dialogId < 0) {
                TLRPC.Chat chat = mc.getChat(-schedule.dialogId);
                if (schedule.topicId != 0) {
                    TLRPC.TL_forumTopic topic = mc.getTopicsController().findTopic(-schedule.dialogId, schedule.topicId);
                    if (topic != null) {
                        ForumUtilities.setTopicIcon(avatarImageView, topic, false, true, resourcesProvider);
                        title = chat != null && !TextUtils.isEmpty(chat.title)
                                ? chat.title + " · " + topic.title
                                : topic.title;
                    } else if (chat != null) {
                        avatarDrawable.setInfo(currentAccount, chat);
                        avatarImageView.setForUserOrChat(chat, avatarDrawable);
                        title = chat.title;
                    } else {
                        avatarDrawable.setInfo(schedule.dialogId, "?", null);
                        avatarImageView.setImageDrawable(avatarDrawable);
                        title = String.valueOf(schedule.dialogId);
                    }
                } else {
                    if (chat != null) {
                        avatarDrawable.setInfo(currentAccount, chat);
                        avatarImageView.setForUserOrChat(chat, avatarDrawable);
                        title = chat.title;
                    } else {
                        avatarDrawable.setInfo(schedule.dialogId, "?", null);
                        avatarImageView.setImageDrawable(avatarDrawable);
                        title = String.valueOf(schedule.dialogId);
                    }
                }
            } else {
                TLRPC.User user = mc.getUser(schedule.dialogId);
                if (user != null) {
                    avatarDrawable.setInfo(currentAccount, user);
                    avatarImageView.setForUserOrChat(user, avatarDrawable);
                    title = ContactsController.formatName(user.first_name, user.last_name);
                } else {
                    avatarDrawable.setInfo(schedule.dialogId, "?", null);
                    avatarImageView.setImageDrawable(avatarDrawable);
                    title = String.valueOf(schedule.dialogId);
                }
            }
            titleTextView.setText(title);

            long now = System.currentTimeMillis();
            boolean mutedNow = schedule.enabled && schedule.isEffectivelyMutedNow(now);
            String summary = schedule.enabled
                    ? schedule.formatSummary()
                    : getString(R.string.NotificationMuteScheduleOff) + " · " + schedule.formatSummary();

            if (mutedNow) {
                String badge = getString(R.string.NotificationMuteScheduleMutedNowBadge);
                SpannableStringBuilder ssb = new SpannableStringBuilder(badge + " · " + summary);
                ssb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_text_RedBold, resourcesProvider)),
                        0, badge.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                subtitleTextView.setText(ssb);
            } else {
                subtitleTextView.setText(summary);
            }

            checkBox.setChecked(schedule.enabled, false);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            int x = LocaleController.isRTL ? dp(76) : getMeasuredWidth() - dp(76);
            canvas.drawLine(x, dp(18), x, getMeasuredHeight() - dp(18), Theme.dividerPaint);
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : dp(68), getMeasuredHeight() - 1,
                        getMeasuredWidth() - (LocaleController.isRTL ? dp(68) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }

        public static class Factory extends UItem.UItemFactory<DialogScheduleCell> {
            static {
                setup(new Factory());
            }

            @Override
            public DialogScheduleCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new DialogScheduleCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((DialogScheduleCell) view).bind(adapter.currentAccount, (DialogSchedule) item.object, divider);
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.object instanceof DialogSchedule && b.object instanceof DialogSchedule) {
                    DialogSchedule sa = (DialogSchedule) a.object;
                    DialogSchedule sb = (DialogSchedule) b.object;
                    return sa.dialogId == sb.dialogId && sa.topicId == sb.topicId;
                }
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.object instanceof DialogSchedule && b.object instanceof DialogSchedule) {
                    DialogSchedule sa = (DialogSchedule) a.object;
                    DialogSchedule sb = (DialogSchedule) b.object;
                    return sa.enabled == sb.enabled
                            && TextUtils.equals(sa.formatSummary(), sb.formatSummary())
                            && sa.isEffectivelyMutedNow(System.currentTimeMillis()) == sb.isEffectivelyMutedNow(System.currentTimeMillis());
                }
                return false;
            }

            public static UItem of(int id, DialogSchedule schedule) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = schedule;
                return item;
            }
        }
    }
}
