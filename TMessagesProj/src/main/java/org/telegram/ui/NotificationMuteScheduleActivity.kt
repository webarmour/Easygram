/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ContactsController
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.NotificationMuteScheduleController
import org.telegram.messenger.NotificationMuteScheduleController.DialogSchedule
import org.telegram.messenger.NotificationMuteScheduleController.MuteInterval
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.SimpleActionBarDoneButton
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeBind
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.NumberPicker
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.Text
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import java.util.Locale

class NotificationMuteScheduleActivity : BaseFragment {

    private val dialogId: Long
    private val topicId: Long
    private val batchDialogIds: ArrayList<Long>?
    private val themeResourcesProvider: Theme.ResourcesProvider?

    private var initialSchedule: DialogSchedule? = null
    private lateinit var workingSchedule: DialogSchedule

    private var listView: UniversalRecyclerView? = null
    private var doneButton: SimpleActionBarDoneButton? = null

    constructor(dialogId: Long, topicId: Long, resourcesProvider: Theme.ResourcesProvider?) : super() {
        this.dialogId = dialogId
        this.topicId = topicId
        this.batchDialogIds = null
        this.themeResourcesProvider = resourcesProvider
    }

    constructor(dialogIds: ArrayList<Long>?, resourcesProvider: Theme.ResourcesProvider?) : super() {
        this.dialogId = 0L
        this.topicId = 0L
        this.batchDialogIds = if (dialogIds != null) ArrayList(dialogIds) else ArrayList()
        this.themeResourcesProvider = resourcesProvider
    }

    override fun getResourceProvider(): Theme.ResourcesProvider? = themeResourcesProvider

    override fun useSimpleActionBar(): Boolean = true

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun getListViewForSimpleGlass(): RecyclerListView? = listView

    override fun onFragmentCreate(): Boolean {
        if (batchDialogIds == null) {
            initialSchedule = NotificationMuteScheduleController.getInstance(currentAccount)
                .getSchedule(dialogId, topicId)
            val existing = initialSchedule
            if (existing != null) {
                workingSchedule = existing.copy()
                if (workingSchedule.intervals.isEmpty()) {
                    workingSchedule.intervals.add(MuteInterval())
                }
            } else {
                workingSchedule = DialogSchedule(dialogId, topicId).apply {
                    enabled = true
                    intervals.add(MuteInterval())
                }
            }
        } else {
            initialSchedule = null
            workingSchedule = DialogSchedule(0L, 0L).apply {
                enabled = true
                intervals.add(MuteInterval())
            }
        }
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        setHasOwnBackground(true)
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back)
        simpleActionBar.setTitle(getString(R.string.NotificationMuteScheduleTitle))
        simpleActionBar.setSubtitle(resolveSubtitle())
        simpleActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                } else if (id == DONE_BUTTON) {
                    saveAndFinish()
                }
            }
        })

        doneButton = simpleActionBar.createDoneButton(DONE_BUTTON, getString(R.string.Save))
        checkDone(false)

        val contentView = FrameLayout(context)
        val recyclerView = UniversalRecyclerView(
            this,
            { items, adapter -> fillItems(items, adapter) },
            { item, view, _, _, _ -> onItemClick(item, view) },
            null
        )
        recyclerView.setSections()
        recyclerView.adapter.setApplyBackground(false)
        recyclerView.setSectionsDrawBackground(true)
        listView = recyclerView
        contentView.addView(
            recyclerView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )

        fragmentView = contentView
        return contentView
    }

    private fun resolveSubtitle(): String {
        batchDialogIds?.let {
            return LocaleController.formatString(R.string.NotificationMuteScheduleSelectedChatsCount, it.size)
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            val encChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId))
            val user = encChat?.let { messagesController.getUser(it.user_id) }
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name) ?: ""
            }
            return ""
        }
        if (DialogObject.isChatDialog(dialogId)) {
            if (topicId != 0L) {
                val topic = messagesController.topicsController.findTopic(-dialogId, topicId)
                if (topic != null && !topic.title.isNullOrEmpty()) {
                    return topic.title
                }
            }
            val chat = messagesController.getChat(-dialogId)
            if (chat != null && !chat.title.isNullOrEmpty()) {
                return chat.title
            }
        } else if (DialogObject.isUserDialog(dialogId)) {
            val user = messagesController.getUser(dialogId)
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name) ?: ""
            }
        }
        return ""
    }

    private fun hasChanges(): Boolean {
        val initial = initialSchedule ?: return true
        if (initial.enabled != workingSchedule.enabled) return true
        if (initial.intervals.size != workingSchedule.intervals.size) return true
        for (i in initial.intervals.indices) {
            if (initial.intervals[i] != workingSchedule.intervals[i]) return true
        }
        return false
    }

    private fun checkDone(animated: Boolean) {
        doneButton?.setActive(hasChanges(), animated)
    }

    private fun saveAndFinish() {
        if (workingSchedule.enabled && !workingSchedule.hasValidIntervals()) {
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.NotificationMuteScheduleSelectDaysError))
                    .show()
            }
            return
        }

        // Drop completely empty intervals if there is at least one valid interval
        val cleaned = workingSchedule.intervals.filter { it.daysMask != 0 }.map { it.copy() }
        if (cleaned.isNotEmpty()) {
            workingSchedule.intervals.clear()
            workingSchedule.intervals.addAll(cleaned)
        }

        val controller = NotificationMuteScheduleController.getInstance(currentAccount)
        val batch = batchDialogIds
        if (batch != null) {
            val toSave = batch.map { did ->
                workingSchedule.copy().apply {
                    dialogId = did
                    topicId = 0L
                }
            }
            controller.saveSchedules(toSave)
        } else {
            controller.saveSchedule(workingSchedule)
        }

        finishFragment()
    }

    private fun fillItems(items: ArrayList<UItem>, @Suppress("UNUSED_PARAMETER") adapter: UniversalAdapter) {
        items.add(UItem.asCheck(ID_ENABLE, getString(R.string.NotificationMuteScheduleEnable)).setChecked(workingSchedule.enabled))
        val statusText = workingSchedule.formatStatusText(System.currentTimeMillis())
        items.add(UItem.asShadow(-100, statusText))

        if (workingSchedule.enabled) {
            items.add(UItem.asHeader(getString(R.string.NotificationMuteScheduleIntervals)))
            for (i in workingSchedule.intervals.indices) {
                if (i > 0) {
                    items.add(UItem.asShadow(-200 - i, null))
                }
                val interval = workingSchedule.intervals[i]
                val baseId = i * INTERVAL_STRIDE

                items.add(
                    WeekDaysSelectorCell.Factory.of(baseId + OFFSET_DAYS, interval, interval.daysMask) { newMask ->
                        interval.daysMask = newMask
                        checkDone(true)
                        listView?.adapter?.update(true)
                    }
                )

                items.add(
                    UItem.asCheck(baseId + OFFSET_ALL_DAY, getString(R.string.NotificationMuteScheduleAllDay))
                        .setChecked(interval.allDay)
                )

                if (!interval.allDay) {
                    val startStr = NotificationMuteScheduleController.formatMinute(interval.startMinute)
                    var endStr = NotificationMuteScheduleController.formatMinute(interval.endMinute)
                    if (interval.isOvernight()) {
                        endStr = LocaleController.formatString(R.string.NotificationMuteScheduleNextDay, endStr)
                    }
                    items.add(UItem.asButton(baseId + OFFSET_START, getString(R.string.NotificationMuteScheduleStart), startStr))
                    items.add(UItem.asButton(baseId + OFFSET_END, getString(R.string.NotificationMuteScheduleEnd), endStr))
                }

                if (workingSchedule.intervals.size > 1) {
                    items.add(UItem.asButton(baseId + OFFSET_REMOVE, getString(R.string.NotificationMuteScheduleRemoveInterval)).red())
                }
            }

            if (workingSchedule.intervals.size < NotificationMuteScheduleController.MAX_INTERVALS) {
                items.add(UItem.asShadow(-101, null))
                items.add(
                    UItem.asButton(ID_ADD_INTERVAL, R.drawable.menu_premium_clock_add, getString(R.string.NotificationMuteScheduleAddInterval))
                        .accent()
                )
            }
            items.add(UItem.asShadow(-102, getString(R.string.NotificationMuteScheduleInfo)))
        }

        if (initialSchedule != null && batchDialogIds == null) {
            items.add(UItem.asButton(ID_DELETE_SCHEDULE, getString(R.string.NotificationMuteScheduleDelete)).red())
            items.add(UItem.asShadow(-103, null))
        }
    }

    private fun onItemClick(item: UItem, view: View) {
        when (item.id) {
            ID_ENABLE -> {
                workingSchedule.enabled = !workingSchedule.enabled
                if (workingSchedule.enabled && workingSchedule.intervals.isEmpty()) {
                    workingSchedule.intervals.add(MuteInterval())
                }
                if (view is TextCheckCell) {
                    view.isChecked = workingSchedule.enabled
                }
                listView?.adapter?.update(true)
                checkDone(true)
                return
            }
            ID_ADD_INTERVAL -> {
                if (workingSchedule.intervals.size < NotificationMuteScheduleController.MAX_INTERVALS) {
                    var usedMask = 0
                    for (interval in workingSchedule.intervals) {
                        usedMask = usedMask or interval.daysMask
                    }
                    val remaining = NotificationMuteScheduleController.DAYS_ALL and usedMask.inv()
                    if (remaining != 0) {
                        workingSchedule.intervals.add(MuteInterval(remaining, true, 0, 0))
                    } else {
                        workingSchedule.intervals.add(MuteInterval(NotificationMuteScheduleController.DAYS_ALL, false, 9 * 60, 18 * 60))
                    }
                    listView?.adapter?.update(true)
                    checkDone(true)
                }
                return
            }
            ID_DELETE_SCHEDULE -> {
                val activity = parentActivity ?: return
                val dialog = AlertDialog.Builder(activity, themeResourcesProvider)
                    .setTitle(getString(R.string.NotificationMuteScheduleDeleteConfirmTitle))
                    .setMessage(getString(R.string.NotificationMuteScheduleDeleteConfirm))
                    .setPositiveButton(getString(R.string.Delete)) { _, _ ->
                        NotificationMuteScheduleController.getInstance(currentAccount)
                            .removeSchedule(dialogId, topicId)
                        finishFragment()
                    }
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .create()
                showDialog(dialog)
                (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)?.let { button ->
                    ThemeBind.textColor(button, Theme.key_text_RedBold, themeResourcesProvider)
                }
                return
            }
        }

        if (item.id >= 0) {
            val intervalIndex = item.id / INTERVAL_STRIDE
            val offset = item.id % INTERVAL_STRIDE
            val interval = workingSchedule.intervals.getOrNull(intervalIndex) ?: return
            when (offset) {
                OFFSET_ALL_DAY -> {
                    interval.allDay = !interval.allDay
                    if (view is TextCheckCell) {
                        view.isChecked = interval.allDay
                    }
                    listView?.adapter?.update(true)
                    checkDone(true)
                }
                OFFSET_START -> {
                    showTimePicker(
                        context,
                        getString(R.string.NotificationMuteScheduleStartPicker),
                        interval.startMinute,
                        themeResourcesProvider
                    ) { picked ->
                        interval.startMinute = picked
                        if (view is TextCell) {
                            view.setValue(NotificationMuteScheduleController.formatMinute(picked), true)
                        }
                        listView?.adapter?.update(true)
                        checkDone(true)
                    }
                }
                OFFSET_END -> {
                    showTimePicker(
                        context,
                        getString(R.string.NotificationMuteScheduleEndPicker),
                        interval.endMinute,
                        themeResourcesProvider
                    ) { picked ->
                        interval.endMinute = picked
                        listView?.adapter?.update(true)
                        checkDone(true)
                    }
                }
                OFFSET_REMOVE -> {
                    if (workingSchedule.intervals.size > 1) {
                        workingSchedule.intervals.removeAt(intervalIndex)
                        listView?.adapter?.update(true)
                        checkDone(true)
                    }
                }
            }
        }
    }

    class WeekDaysSelectorCell(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : LinearLayout(context) {

        private val dayViews = ArrayList<TextView>(7)
        private var daysMask: Int = 0
        private var needDivider: Boolean = false
        private var onMaskChanged: Utilities.Callback<Int>? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setWillNotDraw(false)

            for (dayIdx in 0 until 7) {
                val tv = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                    typeface = AndroidUtilities.bold()
                    isSingleLine = true
                    text = NotificationMuteScheduleController.getShortDayName(dayIdx)
                    setOnClickListener {
                        daysMask = daysMask xor (1 shl dayIdx)
                        updateButtons()
                        onMaskChanged?.run(daysMask)
                    }
                }
                dayViews.add(tv)
                val lp = LayoutParams(0, dp(36f), 1f).apply {
                    leftMargin = dp(3f)
                    rightMargin = dp(3f)
                }
                addView(tv, lp)
            }
        }

        fun bind(mask: Int, divider: Boolean, listener: Utilities.Callback<Int>?) {
            daysMask = mask
            needDivider = divider
            onMaskChanged = listener
            updateButtons()
            invalidate()
        }

        private fun updateButtons() {
            val activeBg = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
            val activePressed = Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)
            val activeText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider)
            val grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider)
            val inactiveBg = Theme.multAlpha(grayText, 0.14f)
            val inactivePressed = Theme.multAlpha(grayText, 0.24f)
            val inactiveText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)

            for (i in 0 until 7) {
                val selected = (daysMask and (1 shl i)) != 0
                val tv = dayViews[i]
                tv.text = NotificationMuteScheduleController.getShortDayName(i)
                tv.setTextColor(if (selected) activeText else inactiveText)
                tv.background = Theme.createSimpleSelectorRoundRectDrawable(
                    dp(18f),
                    if (selected) activeBg else inactiveBg,
                    if (selected) activePressed else inactivePressed
                )
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(56f), MeasureSpec.EXACTLY)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (needDivider) {
                canvas.drawLine(
                    if (LocaleController.isRTL) 0f else dp(20f).toFloat(),
                    (measuredHeight - 1).toFloat(),
                    (measuredWidth - if (LocaleController.isRTL) dp(20f) else 0).toFloat(),
                    (measuredHeight - 1).toFloat(),
                    Theme.dividerPaint
                )
            }
        }

        class Factory : UItem.UItemFactory<WeekDaysSelectorCell>() {
            override fun createView(
                context: Context,
                listView: RecyclerListView,
                currentAccount: Int,
                classGuid: Int,
                resourcesProvider: Theme.ResourcesProvider?
            ): WeekDaysSelectorCell = WeekDaysSelectorCell(context, resourcesProvider)

            override fun bindView(
                view: View,
                item: UItem,
                divider: Boolean,
                adapter: UniversalAdapter,
                listView: UniversalRecyclerView
            ) {
                (view as WeekDaysSelectorCell).bind(item.intValue, divider, item.intCallback)
            }

            override fun isClickable(): Boolean = false

            override fun equals(a: UItem, b: UItem): Boolean = a.id == b.id

            // Compare owning MuteInterval identity too so removing an earlier interval rebinds the callback
            override fun contentsEquals(a: UItem, b: UItem): Boolean =
                a.intValue == b.intValue && a.`object` === b.`object`

            companion object {
                init {
                    setup(Factory())
                }

                fun of(id: Int, ownerInterval: MuteInterval, daysMask: Int, onChanged: Utilities.Callback<Int>): UItem {
                    return UItem.ofFactory(Factory::class.java).apply {
                        this.id = id
                        this.`object` = ownerInterval
                        this.intValue = daysMask
                        this.intCallback = onChanged
                    }
                }
            }
        }
    }

    companion object {
        private const val DONE_BUTTON = 1
        private const val ID_ENABLE = -1
        private const val ID_ADD_INTERVAL = -2
        private const val ID_DELETE_SCHEDULE = -3
        private const val INTERVAL_STRIDE = 10
        private const val OFFSET_DAYS = 0
        private const val OFFSET_ALL_DAY = 1
        private const val OFFSET_START = 2
        private const val OFFSET_END = 3
        private const val OFFSET_REMOVE = 4

        private fun showTimePicker(
            context: Context?,
            title: String,
            initialMinute: Int,
            resourcesProvider: Theme.ResourcesProvider?,
            onPicked: (Int) -> Unit
        ) {
            if (context == null) return
            val colors = AlertsCreator.ScheduleDatePickerColors(resourcesProvider)
            val builder = BottomSheet.Builder(context, false, resourcesProvider)
            builder.setApplyBottomPadding(false)

            val initHour = Utilities.clamp(initialMinute / 60, 23, 0)
            val initMin = Utilities.clamp(initialMinute % 60, 59, 0)

            val hourPicker = object : NumberPicker(context, resourcesProvider) {
                override fun getContentDescription(value: Int): CharSequence =
                    LocaleController.formatPluralString("Hours", value)
            }.apply {
                minValue = 0
                maxValue = 23
                wrapSelectorWheel = true
                setAllItemsCount(24)
                setItemCount(5)
                setTextColor(colors.textColor)
                gravity = Gravity.RIGHT
                setTextOffset(-dp(12f))
                value = initHour
                setFormatter { value ->
                    if (LocaleController.is24HourFormat) {
                        String.format(Locale.US, "%02d", value)
                    } else {
                        val h = value % 12
                        String.format(Locale.US, "%02d", if (h == 0) 12 else h)
                    }
                }
            }

            val minutePicker = object : NumberPicker(context, resourcesProvider) {
                override fun getContentDescription(value: Int): CharSequence =
                    LocaleController.formatPluralString("Minutes", value)
            }.apply {
                minValue = 0
                maxValue = 59
                wrapSelectorWheel = true
                setAllItemsCount(60)
                setItemCount(5)
                setTextColor(colors.textColor)
                gravity = Gravity.LEFT
                setTextOffset(dp(12f))
                value = initMin
                setFormatter { value -> String.format(Locale.US, "%02d", value) }
            }

            val pickerRow = object : LinearLayout(context) {
                private val separatorText = Text(":", 18f)
                private var isAM = false
                private var ampmText: Text? = null

                override fun dispatchDraw(canvas: Canvas) {
                    separatorText.draw(canvas, (width - separatorText.currentWidth) / 2f, height / 2f, colors.textColor, 1f)
                    if (!LocaleController.is24HourFormat) {
                        val am = hourPicker.value < 12
                        if (isAM != am || ampmText == null) {
                            isAM = am
                            ampmText = Text(if (am) "AM" else "PM", 18f)
                        }
                        ampmText?.draw(canvas, width / 2f + dp(48f), height / 2f + dp(1f), colors.textColor, 1f)
                    }
                    super.dispatchDraw(canvas)
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 1f
                addView(hourPicker, LayoutHelper.createLinear(0, 54 * 5, 0.5f))
                addView(minutePicker, LayoutHelper.createLinear(0, 54 * 5, 0.5f))
            }
            hourPicker.setOnValueChangedListener { _, _, _ -> pickerRow.invalidate() }

            val container = object : LinearLayout(context) {
                private var ignoreLayout = false

                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    ignoreLayout = true
                    val count = if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) 3 else 5
                    hourPicker.setItemCount(count)
                    minutePicker.setItemCount(count)
                    hourPicker.layoutParams.height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT.toFloat()) * count
                    minutePicker.layoutParams.height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT.toFloat()) * count
                    ignoreLayout = false
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }

                override fun requestLayout() {
                    if (ignoreLayout) return
                    super.requestLayout()
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleLayout = FrameLayout(context)
            val titleView = TextView(context).apply {
                text = title
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                typeface = AndroidUtilities.bold()
            }
            titleLayout.addView(
                titleView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.TOP, 0f, 12f, 0f, 0f)
            )
            container.addView(
                titleLayout,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT or Gravity.TOP, 22, 0, 0, 4)
            )
            container.addView(
                pickerRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0, 12)
            )

            var sheet: BottomSheet? = null
            val button = ButtonWithCounterView(context, resourcesProvider).setRound().apply {
                setText(getString(R.string.Select), false)
                setOnClickListener {
                    val picked = hourPicker.value * 60 + minutePicker.value
                    sheet?.dismiss()
                    onPicked(picked)
                }
            }
            container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 12, 16, 12))

            builder.setCustomView(container)
            sheet = builder.show().apply {
                setBackgroundColor(colors.backgroundColor)
                fixNavigationBar(colors.backgroundColor)
            }
        }
    }
}
