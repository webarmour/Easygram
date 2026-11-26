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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationMuteScheduleController;
import org.telegram.messenger.NotificationMuteScheduleController.DialogSchedule;
import org.telegram.messenger.NotificationMuteScheduleController.MuteInterval;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleActionBarDoneButton;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Locale;

public class NotificationMuteScheduleActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;
    private static final int ID_ENABLE = -1;
    private static final int ID_ADD_INTERVAL = -2;
    private static final int ID_DELETE_SCHEDULE = -3;
    private static final int INTERVAL_STRIDE = 10;
    private static final int OFFSET_DAYS = 0;
    private static final int OFFSET_ALL_DAY = 1;
    private static final int OFFSET_START = 2;
    private static final int OFFSET_END = 3;
    private static final int OFFSET_REMOVE = 4;

    private final long dialogId;
    private final long topicId;
    @Nullable
    private final ArrayList<Long> batchDialogIds;
    private final Theme.ResourcesProvider resourcesProvider;

    @Nullable
    private DialogSchedule initialSchedule;
    private DialogSchedule workingSchedule;

    private UniversalRecyclerView listView;
    private SimpleActionBarDoneButton doneButton;

    public NotificationMuteScheduleActivity(long dialogId, long topicId, Theme.ResourcesProvider resourcesProvider) {
        super();
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.batchDialogIds = null;
        this.resourcesProvider = resourcesProvider;
    }

    public NotificationMuteScheduleActivity(ArrayList<Long> dialogIds, Theme.ResourcesProvider resourcesProvider) {
        super();
        this.dialogId = 0;
        this.topicId = 0;
        this.batchDialogIds = dialogIds != null ? new ArrayList<>(dialogIds) : new ArrayList<>();
        this.resourcesProvider = resourcesProvider;
    }

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return resourcesProvider;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        if (batchDialogIds == null) {
            initialSchedule = NotificationMuteScheduleController.getInstance(currentAccount).getSchedule(dialogId, topicId);
            if (initialSchedule != null) {
                workingSchedule = initialSchedule.copy();
                if (workingSchedule.intervals.isEmpty()) {
                    workingSchedule.intervals.add(new MuteInterval());
                }
            } else {
                workingSchedule = new DialogSchedule(dialogId, topicId);
                workingSchedule.enabled = true;
                workingSchedule.intervals.add(new MuteInterval());
            }
        } else {
            initialSchedule = null;
            workingSchedule = new DialogSchedule(0, 0);
            workingSchedule.enabled = true;
            workingSchedule.intervals.add(new MuteInterval());
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(getString(R.string.NotificationMuteScheduleTitle));
        simpleActionBar.setSubtitle(resolveSubtitle());
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_BUTTON) {
                    saveAndFinish();
                }
            }
        });

        doneButton = simpleActionBar.createDoneButton(DONE_BUTTON, getString(R.string.Save));
        checkDone(false);

        FrameLayout contentView = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setSectionsDrawBackground(true);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView = contentView;
    }

    private String resolveSubtitle() {
        if (batchDialogIds != null) {
            return LocaleController.formatString(R.string.NotificationMuteScheduleSelectedChatsCount, batchDialogIds.size());
        }
        if (dialogId < 0) {
            if (topicId != 0) {
                TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(-dialogId, topicId);
                if (topic != null && !TextUtils.isEmpty(topic.title)) {
                    return topic.title;
                }
            }
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        } else if (dialogId > 0) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        }
        return "";
    }

    private boolean hasChanges() {
        if (initialSchedule == null) {
            return true;
        }
        if (initialSchedule.enabled != workingSchedule.enabled) {
            return true;
        }
        if (initialSchedule.intervals.size() != workingSchedule.intervals.size()) {
            return true;
        }
        for (int i = 0; i < initialSchedule.intervals.size(); i++) {
            if (!initialSchedule.intervals.get(i).equals(workingSchedule.intervals.get(i))) {
                return true;
            }
        }
        return false;
    }

    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        doneButton.setActive(hasChanges(), animated);
    }

    private void saveAndFinish() {
        if (workingSchedule.enabled && !workingSchedule.hasValidIntervals()) {
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.NotificationMuteScheduleSelectDaysError)).show();
            }
            return;
        }

        // Drop completely empty intervals if there is at least one valid interval
        ArrayList<MuteInterval> cleaned = new ArrayList<>();
        for (int i = 0; i < workingSchedule.intervals.size(); i++) {
            MuteInterval interval = workingSchedule.intervals.get(i);
            if (interval.daysMask != 0) {
                cleaned.add(interval.copy());
            }
        }
        if (!cleaned.isEmpty()) {
            workingSchedule.intervals.clear();
            workingSchedule.intervals.addAll(cleaned);
        }

        NotificationMuteScheduleController controller = NotificationMuteScheduleController.getInstance(currentAccount);
        if (batchDialogIds != null) {
            for (int i = 0; i < batchDialogIds.size(); i++) {
                long did = batchDialogIds.get(i);
                DialogSchedule copy = workingSchedule.copy();
                copy.dialogId = did;
                copy.topicId = 0;
                controller.saveSchedule(copy);
            }
        } else {
            controller.saveSchedule(workingSchedule);
        }

        finishFragment();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLE, getString(R.string.NotificationMuteScheduleEnable)).setChecked(workingSchedule.enabled));
        String statusText = workingSchedule.formatStatusText(System.currentTimeMillis());
        items.add(UItem.asShadow(-100, statusText));

        if (workingSchedule.enabled) {
            items.add(UItem.asHeader(getString(R.string.NotificationMuteScheduleIntervals)));
            for (int i = 0; i < workingSchedule.intervals.size(); i++) {
                if (i > 0) {
                    items.add(UItem.asShadow(-200 - i, null));
                }
                MuteInterval interval = workingSchedule.intervals.get(i);
                int baseId = i * INTERVAL_STRIDE;

                UItem daysItem = WeekDaysSelectorCell.Factory.of(baseId + OFFSET_DAYS, interval.daysMask, newMask -> {
                    interval.daysMask = newMask;
                    checkDone(true);
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
                items.add(daysItem);

                items.add(UItem.asCheck(baseId + OFFSET_ALL_DAY, getString(R.string.NotificationMuteScheduleAllDay))
                        .setChecked(interval.allDay));

                if (!interval.allDay) {
                    String startStr = NotificationMuteScheduleController.formatMinute(interval.startMinute);
                    String endStr = NotificationMuteScheduleController.formatMinute(interval.endMinute);
                    if (interval.isOvernight()) {
                        endStr = LocaleController.formatString(R.string.NotificationMuteScheduleNextDay, endStr);
                    }
                    items.add(UItem.asButton(baseId + OFFSET_START, getString(R.string.NotificationMuteScheduleStart), startStr));
                    items.add(UItem.asButton(baseId + OFFSET_END, getString(R.string.NotificationMuteScheduleEnd), endStr));
                }

                if (workingSchedule.intervals.size() > 1) {
                    items.add(UItem.asButton(baseId + OFFSET_REMOVE, getString(R.string.NotificationMuteScheduleRemoveInterval)).red());
                }
            }

            if (workingSchedule.intervals.size() < NotificationMuteScheduleController.MAX_INTERVALS) {
                items.add(UItem.asShadow(-101, null));
                items.add(UItem.asButton(ID_ADD_INTERVAL, R.drawable.menu_premium_clock_add, getString(R.string.NotificationMuteScheduleAddInterval)).accent());
            }
            items.add(UItem.asShadow(-102, getString(R.string.NotificationMuteScheduleInfo)));
        }

        if (initialSchedule != null && batchDialogIds == null) {
            items.add(UItem.asButton(ID_DELETE_SCHEDULE, getString(R.string.NotificationMuteScheduleDelete)).red());
            items.add(UItem.asShadow(-103, null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLE) {
            workingSchedule.enabled = !workingSchedule.enabled;
            if (workingSchedule.enabled && workingSchedule.intervals.isEmpty()) {
                workingSchedule.intervals.add(new MuteInterval());
            }
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(workingSchedule.enabled);
            }
            listView.adapter.update(true);
            checkDone(true);
            return;
        }

        if (item.id == ID_ADD_INTERVAL) {
            if (workingSchedule.intervals.size() < NotificationMuteScheduleController.MAX_INTERVALS) {
                // Default new interval to weekends all day if weekdays already used, else standard evening
                int usedMask = 0;
                for (int i = 0; i < workingSchedule.intervals.size(); i++) {
                    usedMask |= workingSchedule.intervals.get(i).daysMask;
                }
                int remaining = NotificationMuteScheduleController.DAYS_ALL & ~usedMask;
                if (remaining != 0) {
                    workingSchedule.intervals.add(new MuteInterval(remaining, true, 0, 0));
                } else {
                    workingSchedule.intervals.add(new MuteInterval(NotificationMuteScheduleController.DAYS_ALL, false, 9 * 60, 18 * 60));
                }
                listView.adapter.update(true);
                checkDone(true);
            }
            return;
        }

        if (item.id == ID_DELETE_SCHEDULE) {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourcesProvider)
                    .setTitle(getString(R.string.NotificationMuteScheduleDeleteConfirmTitle))
                    .setMessage(getString(R.string.NotificationMuteScheduleDeleteConfirm))
                    .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                        NotificationMuteScheduleController.getInstance(currentAccount).removeSchedule(dialogId, topicId);
                        finishFragment();
                    })
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .create();
            showDialog(dialog);
            TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                ThemeBind.textColor(button, Theme.key_text_RedBold, resourcesProvider);
            }
            return;
        }

        if (item.id >= 0) {
            int intervalIndex = item.id / INTERVAL_STRIDE;
            int offset = item.id % INTERVAL_STRIDE;
            if (intervalIndex >= workingSchedule.intervals.size()) {
                return;
            }
            MuteInterval interval = workingSchedule.intervals.get(intervalIndex);
            if (offset == OFFSET_ALL_DAY) {
                interval.allDay = !interval.allDay;
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(interval.allDay);
                }
                listView.adapter.update(true);
                checkDone(true);
            } else if (offset == OFFSET_START) {
                showTimePicker(getContext(), getString(R.string.NotificationMuteScheduleStartPicker), interval.startMinute, resourcesProvider, picked -> {
                    interval.startMinute = picked;
                    if (view instanceof TextCell) {
                        ((TextCell) view).setValue(NotificationMuteScheduleController.formatMinute(picked), true);
                    }
                    listView.adapter.update(true);
                    checkDone(true);
                });
            } else if (offset == OFFSET_END) {
                showTimePicker(getContext(), getString(R.string.NotificationMuteScheduleEndPicker), interval.endMinute, resourcesProvider, picked -> {
                    interval.endMinute = picked;
                    listView.adapter.update(true);
                    checkDone(true);
                });
            } else if (offset == OFFSET_REMOVE) {
                if (workingSchedule.intervals.size() > 1) {
                    workingSchedule.intervals.remove(intervalIndex);
                    listView.adapter.update(true);
                    checkDone(true);
                }
            }
        }
    }

    private static void showTimePicker(Context context, String title, int initialMinute, Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Integer> onPicked) {
        if (context == null) {
            return;
        }
        AlertsCreator.ScheduleDatePickerColors colors = new AlertsCreator.ScheduleDatePickerColors(resourcesProvider);
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        builder.setApplyBottomPadding(false);

        int initHour = Utilities.clamp(initialMinute / 60, 23, 0);
        int initMin = Utilities.clamp(initialMinute % 60, 59, 0);

        final NumberPicker hourPicker = new NumberPicker(context, resourcesProvider) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Hours", value);
            }
        };
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setWrapSelectorWheel(true);
        hourPicker.setAllItemsCount(24);
        hourPicker.setItemCount(5);
        hourPicker.setTextColor(colors.textColor);
        hourPicker.setGravity(Gravity.RIGHT);
        hourPicker.setTextOffset(-dp(12));
        hourPicker.setValue(initHour);
        hourPicker.setFormatter(value -> {
            if (LocaleController.is24HourFormat) {
                return String.format(Locale.US, "%02d", value);
            }
            int h = value % 12;
            if (h == 0) h = 12;
            return String.format(Locale.US, "%02d", h);
        });

        final NumberPicker minutePicker = new NumberPicker(context, resourcesProvider) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Minutes", value);
            }
        };
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setWrapSelectorWheel(true);
        minutePicker.setAllItemsCount(60);
        minutePicker.setItemCount(5);
        minutePicker.setTextColor(colors.textColor);
        minutePicker.setGravity(Gravity.LEFT);
        minutePicker.setTextOffset(dp(12));
        minutePicker.setValue(initMin);
        minutePicker.setFormatter(value -> String.format(Locale.US, "%02d", value));

        LinearLayout pickerRow = new LinearLayout(context) {
            private final Text separatorText = new Text(":", 18);
            private boolean isAM;
            private Text ampmText;

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                separatorText.draw(canvas, (getWidth() - separatorText.getCurrentWidth()) / 2f, getHeight() / 2f, colors.textColor, 1f);
                if (!LocaleController.is24HourFormat) {
                    boolean am = hourPicker.getValue() < 12;
                    if (this.isAM != am || ampmText == null) {
                        this.isAM = am;
                        ampmText = new Text(am ? "AM" : "PM", 18);
                    }
                    ampmText.draw(canvas, getWidth() / 2f + dp(48), getHeight() / 2f + dp(1), colors.textColor, 1f);
                }
                super.dispatchDraw(canvas);
            }
        };
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setWeightSum(1f);
        pickerRow.addView(hourPicker, LayoutHelper.createLinear(0, 54 * 5, 0.5f));
        pickerRow.addView(minutePicker, LayoutHelper.createLinear(0, 54 * 5, 0.5f));
        hourPicker.setOnValueChangedListener((p, oldVal, newVal) -> pickerRow.invalidate());

        LinearLayout container = new LinearLayout(context) {
            boolean ignoreLayout = false;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                ignoreLayout = true;
                int count = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y ? 3 : 5;
                hourPicker.setItemCount(count);
                minutePicker.setItemCount(count);
                hourPicker.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                minutePicker.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                ignoreLayout = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) return;
                super.requestLayout();
            }
        };
        container.setOrientation(LinearLayout.VERTICAL);

        FrameLayout titleLayout = new FrameLayout(context);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(colors.textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleLayout.addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 12, 0, 0));
        container.addView(titleLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 22, 0, 0, 4));
        container.addView(pickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0, 12));

        final BottomSheet[] sheet = new BottomSheet[1];
        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString(R.string.Select), false);
        button.setOnClickListener(v -> {
            int picked = hourPicker.getValue() * 60 + minutePicker.getValue();
            if (sheet[0] != null) {
                sheet[0].dismiss();
            }
            if (onPicked != null) {
                onPicked.run(picked);
            }
        });
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 12, 16, 12));

        builder.setCustomView(container);
        sheet[0] = builder.show();
        sheet[0].setBackgroundColor(colors.backgroundColor);
        sheet[0].fixNavigationBar(colors.backgroundColor);
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

    public static class WeekDaysSelectorCell extends LinearLayout {

        private final TextView[] dayViews = new TextView[7];
        private final Theme.ResourcesProvider resourcesProvider;
        private int daysMask;
        private boolean needDivider;
        private Utilities.Callback<Integer> onMaskChanged;

        public WeekDaysSelectorCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(10), dp(12), dp(10));
            setWillNotDraw(false);

            for (int i = 0; i < 7; i++) {
                final int dayIdx = i;
                TextView tv = new TextView(context);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setSingleLine(true);
                tv.setText(NotificationMuteScheduleController.getShortDayName(dayIdx));
                tv.setOnClickListener(v -> {
                    daysMask ^= (1 << dayIdx);
                    updateButtons();
                    if (onMaskChanged != null) {
                        onMaskChanged.run(daysMask);
                    }
                });
                dayViews[i] = tv;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                addView(tv, lp);
            }
        }

        public void bind(int mask, boolean divider, Utilities.Callback<Integer> listener) {
            this.daysMask = mask;
            this.needDivider = divider;
            this.onMaskChanged = listener;
            updateButtons();
            invalidate();
        }

        private void updateButtons() {
            int activeBg = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int activePressed = Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider);
            int activeText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider);
            int inactiveBg = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), 0.14f);
            int inactivePressed = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), 0.24f);
            int inactiveText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);

            for (int i = 0; i < 7; i++) {
                boolean selected = (daysMask & (1 << i)) != 0;
                TextView tv = dayViews[i];
                tv.setText(NotificationMuteScheduleController.getShortDayName(i));
                tv.setTextColor(selected ? activeText : inactiveText);
                tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), selected ? activeBg : inactiveBg, selected ? activePressed : inactivePressed));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : dp(20), getMeasuredHeight() - 1,
                        getMeasuredWidth() - (LocaleController.isRTL ? dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }

        public static class Factory extends UItem.UItemFactory<WeekDaysSelectorCell> {
            static {
                setup(new Factory());
            }

            @Override
            public WeekDaysSelectorCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new WeekDaysSelectorCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((WeekDaysSelectorCell) view).bind(item.intValue, divider, item.intCallback);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.intValue == b.intValue;
            }

            public static UItem of(int id, int daysMask, Utilities.Callback<Integer> onChanged) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.intValue = daysMask;
                item.intCallback = onChanged;
                return item;
            }
        }
    }
}
