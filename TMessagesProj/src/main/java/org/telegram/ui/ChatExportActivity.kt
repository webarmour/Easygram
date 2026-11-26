/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatExportController
import org.telegram.messenger.ChatObject
import org.telegram.messenger.ContactsController
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.NumberPicker
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import org.telegram.utils.glass.BaseFragmentSimpleGlass
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

class ChatExportActivity(
    private val dialogId: Long,
    private val mergeDialogId: Long,
    private val topicId: Long,
    private val isTopic: Boolean,
    private val isSavedMode: Boolean,
    chatTitle: String?,
    chatSubtitle: String?,
    private val themeResourcesProvider: Theme.ResourcesProvider?
) : BaseFragment() {

    private val chatTitle: String = chatTitle ?: ""
    private val chatSubtitle: String = chatSubtitle ?: ""

    private var listView: UniversalRecyclerView? = null
    private var bottomContainer: FrameLayout? = null
    private var exportButton: ButtonWithCounterView? = null

    // Calendar & range state (YYYYMMDD integer keys, or 0 for "all time")
    private var displayedYear: Int = 0
    private var displayedMonth: Int = 0 // 0..11
    private var todayKey: Int = 0
    private var todayYear: Int = 0
    private var todayMonth: Int = 0
    private var selectedStartKey: Int = 0
    private var selectedEndKey: Int = 0
    private var activePreset: Int = PRESET_ALL_TIME
    private var includeMedia: Boolean = false

    private var activeExporter: ChatExportController? = null
    private var progressDialog: AlertDialog? = null

    override fun getResourceProvider(): Theme.ResourcesProvider? = themeResourcesProvider

    override fun useSimpleActionBar(): Boolean = true

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun getListViewForSimpleGlass(): RecyclerListView? = listView

    override fun onFragmentCreate(): Boolean {
        refreshToday()
        displayedYear = todayYear
        displayedMonth = todayMonth
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        activeExporter?.cancel()
        activeExporter = null
        dismissProgressDialog()
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        setHasOwnBackground(true)
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back)
        simpleActionBar.setTitle(getString(R.string.ExportChatTitle))
        simpleActionBar.setSubtitle(resolveChatTitle())
        simpleActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

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

        val bottom = FrameLayout(context).apply {
            setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
        }
        bottomContainer = bottom

        val btn = ButtonWithCounterView(context, themeResourcesProvider).setRound().apply {
            setText(getString(R.string.ExportChatStartButton), false)
            setOnClickListener { startExport() }
        }
        exportButton = btn
        bottom.addView(btn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER))

        contentView.addView(
            bottom,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM)
        )

        fragmentView = contentView
        return contentView
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        applyInsets(mSystemInsets.top, mSystemInsets.bottom)
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        super.onInsets(left, top, right, bottom)
        applyInsets(top, bottom)
    }

    private fun applyInsets(top: Int, bottom: Int) {
        bottomContainer?.setPadding(dp(16f), dp(10f), dp(16f), dp(12f) + bottom)
        listView?.let {
            BaseFragmentSimpleGlass.setListViewParams(it, top, bottom, ActionBar.getCurrentActionBarHeight(), dp(72f))
        }
    }

    private fun refreshToday() {
        val today = Calendar.getInstance()
        todayYear = today.get(Calendar.YEAR)
        todayMonth = today.get(Calendar.MONTH)
        todayKey = toDayKey(todayYear, todayMonth, today.get(Calendar.DAY_OF_MONTH))
    }

    private fun resolveChatTitle(): String {
        if (chatTitle.isNotEmpty()) return chatTitle
        if (dialogId < 0) {
            if (isTopic && topicId != 0L) {
                val topic = messagesController.topicsController.findTopic(-dialogId, topicId)
                if (topic != null && !topic.title.isNullOrEmpty()) {
                    return topic.title
                }
            }
            val chat = messagesController.getChat(-dialogId)
            if (chat != null && !chat.title.isNullOrEmpty()) {
                return chat.title
            }
        } else if (dialogId > 0) {
            val user = messagesController.getUser(dialogId)
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name) ?: ""
            }
        }
        return ""
    }

    private fun fillItems(items: ArrayList<UItem>, @Suppress("UNUSED_PARAMETER") adapter: UniversalAdapter) {
        items.add(UItem.asHeader(getString(R.string.ExportChatPeriod)))
        items.add(PresetChipsCell.Factory.of(ID_PRESETS, activePreset) { preset -> applyPreset(preset) })
        items.add(
            CalendarRangeCell.Factory.of(
                ID_CALENDAR,
                CalendarState(
                    year = displayedYear,
                    month = displayedMonth,
                    todayKey = todayKey,
                    todayYear = todayYear,
                    todayMonth = todayMonth,
                    startKey = selectedStartKey,
                    endKey = selectedEndKey,
                    summaryLabel = formatSelectedPeriodLabel()
                ),
                object : CalendarListener {
                    override fun onPrevMonth() {
                        displayedMonth--
                        if (displayedMonth < 0) {
                            displayedMonth = 11
                            displayedYear--
                        }
                        if (displayedYear < MIN_CALENDAR_YEAR) {
                            displayedYear = MIN_CALENDAR_YEAR
                            displayedMonth = 0
                        }
                        updateList()
                    }

                    override fun onNextMonth() {
                        refreshToday()
                        if (displayedYear < todayYear || (displayedYear == todayYear && displayedMonth < todayMonth)) {
                            displayedMonth++
                            if (displayedMonth > 11) {
                                displayedMonth = 0
                                displayedYear++
                            }
                            updateList()
                        }
                    }

                    override fun onMonthTitleClick() = showMonthYearPicker()

                    override fun onDayClick(dayKey: Int) = handleDaySelected(dayKey)

                    override fun onResetClick() = applyPreset(PRESET_ALL_TIME)
                }
            )
        )
        items.add(UItem.asShadow(-100, getString(R.string.ExportChatCalendarHint)))

        items.add(UItem.asHeader(getString(R.string.ExportChatOptions)))
        items.add(UItem.asCheck(ID_INCLUDE_MEDIA, getString(R.string.ExportChatIncludeMedia)).setChecked(includeMedia))
        items.add(
            UItem.asShadow(
                -101,
                getString(if (includeMedia) R.string.ExportChatIncludeMediaInfoOn else R.string.ExportChatIncludeMediaInfoOff)
            )
        )
    }

    private fun onItemClick(item: UItem, view: View) {
        if (item.id == ID_INCLUDE_MEDIA) {
            includeMedia = !includeMedia
            if (view is TextCheckCell) {
                view.isChecked = includeMedia
            }
            updateList()
        }
    }

    private fun updateList() {
        listView?.adapter?.update(true)
    }

    private fun applyPreset(preset: Int) {
        refreshToday()
        activePreset = preset
        val cal = Calendar.getInstance()
        val endKey = todayKey

        when (preset) {
            PRESET_ALL_TIME -> {
                selectedStartKey = 0
                selectedEndKey = 0
            }
            PRESET_7_DAYS -> {
                cal.add(Calendar.DAY_OF_MONTH, -6)
                selectedStartKey = toDayKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                selectedEndKey = endKey
                displayedYear = todayYear
                displayedMonth = todayMonth
            }
            PRESET_30_DAYS -> {
                cal.add(Calendar.DAY_OF_MONTH, -29)
                selectedStartKey = toDayKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                selectedEndKey = endKey
                displayedYear = todayYear
                displayedMonth = todayMonth
            }
            PRESET_THIS_MONTH -> {
                selectedStartKey = toDayKey(todayYear, todayMonth, 1)
                selectedEndKey = endKey
                displayedYear = todayYear
                displayedMonth = todayMonth
            }
        }
        updateList()
    }

    private fun handleDaySelected(dayKey: Int) {
        refreshToday()
        if (dayKey > todayKey) return

        when {
            selectedStartKey == 0 && selectedEndKey == 0 -> {
                selectedStartKey = dayKey
                selectedEndKey = dayKey
            }
            selectedStartKey == selectedEndKey -> {
                when {
                    dayKey == selectedStartKey -> {
                        selectedStartKey = 0
                        selectedEndKey = 0
                    }
                    dayKey < selectedStartKey -> selectedStartKey = dayKey
                    else -> selectedEndKey = dayKey
                }
            }
            else -> {
                selectedStartKey = dayKey
                selectedEndKey = dayKey
            }
        }
        activePreset = if (selectedStartKey == 0 && selectedEndKey == 0) PRESET_ALL_TIME else PRESET_NONE
        updateList()
    }

    private fun formatSelectedPeriodLabel(): String {
        if (selectedStartKey == 0 || selectedEndKey == 0) {
            return getString(R.string.ExportChatSelectedAllTime)
        }
        val locale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()
        val fmt = SimpleDateFormat("d MMM yyyy", locale)
        val startDate = dayKeyToCalendar(selectedStartKey, endOfDay = false).time
        if (selectedStartKey == selectedEndKey) {
            return LocaleController.formatString(R.string.ExportChatSelectedSingleDay, fmt.format(startDate))
        }
        val endDate = dayKeyToCalendar(selectedEndKey, endOfDay = false).time
        // roundToLong() prevents off-by-one across 23-hour spring DST transitions
        val days = 1 + ((endDate.time - startDate.time) / 86_400_000.0).roundToLong().coerceAtLeast(0L)
        return LocaleController.formatString(
            R.string.ExportChatSelectedRange,
            fmt.format(startDate),
            fmt.format(endDate),
            days.toInt()
        )
    }

    private fun showMonthYearPicker() {
        val context = parentActivity ?: return
        refreshToday()

        val builder = BottomSheet.Builder(context, false, themeResourcesProvider)
        builder.setApplyBottomPadding(false)

        val textColor = Theme.getColor(Theme.key_dialogTextBlack, themeResourcesProvider)
        val bgColor = Theme.getColor(Theme.key_dialogBackground, themeResourcesProvider)

        val locale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()
        val rawMonths = DateFormatSymbols(locale).months
        val months = Array(12) { i ->
            val m = rawMonths.getOrNull(i) ?: ""
            if (m.isNotEmpty()) m.substring(0, 1).uppercase(locale) + m.substring(1) else ""
        }

        val monthPicker = NumberPicker(context, themeResourcesProvider).apply {
            minValue = 0
            maxValue = 11
            value = displayedMonth.coerceIn(0, 11)
            wrapSelectorWheel = true
            setFormatter { value -> months.getOrNull(value) ?: "" }
        }

        val yearPicker = NumberPicker(context, themeResourcesProvider).apply {
            minValue = MIN_CALENDAR_YEAR
            maxValue = todayYear.coerceAtLeast(MIN_CALENDAR_YEAR)
            value = displayedYear.coerceIn(MIN_CALENDAR_YEAR, todayYear.coerceAtLeast(MIN_CALENDAR_YEAR))
            wrapSelectorWheel = false
            setFormatter { it.toString() }
        }

        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(monthPicker, LayoutHelper.createLinear(0, 180, 1.3f, 16, 0, 8, 0))
            addView(yearPicker, LayoutHelper.createLinear(0, 180, 1f, 8, 0, 16, 0))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(context).apply {
            text = getString(R.string.ExportChatSelectMonthYear)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
        }
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22f, 16f, 22f, 8f))
        container.addView(pickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 8f))

        var sheet: BottomSheet? = null
        val selectBtn = ButtonWithCounterView(context, themeResourcesProvider).setRound().apply {
            setText(getString(R.string.Select), false)
            setOnClickListener {
                val pickedYear = yearPicker.value
                var pickedMonth = monthPicker.value
                if (pickedYear == todayYear && pickedMonth > todayMonth) {
                    pickedMonth = todayMonth
                }
                displayedYear = pickedYear
                displayedMonth = pickedMonth
                sheet?.dismiss()
                updateList()
            }
        }
        container.addView(selectBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16f, 8f, 16f, 16f))

        builder.setCustomView(container)
        sheet = builder.show().apply {
            setBackgroundColor(bgColor)
            fixNavigationBar(bgColor)
        }
    }

    private fun startExport() {
        val activity = parentActivity ?: return
        if (activeExporter != null) return

        var startSec = 0
        var endSec = 0
        if (selectedStartKey != 0 && selectedEndKey != 0) {
            startSec = (dayKeyToCalendar(selectedStartKey, endOfDay = false).timeInMillis / 1000L).toInt()
            endSec = (dayKeyToCalendar(selectedEndKey, endOfDay = true).timeInMillis / 1000L).toInt()
        }

        val exportWithMedia = includeMedia
        val config = ChatExportController.ExportConfig(
            currentAccount = currentAccount,
            dialogId = dialogId,
            mergeDialogId = mergeDialogId,
            topicId = topicId,
            isTopic = isTopic,
            isSavedMode = isSavedMode,
            chatTitle = resolveChatTitle(),
            chatSubtitle = chatSubtitle,
            startDateSec = startSec,
            endDateSec = endSec,
            periodDescription = formatSelectedPeriodLabel(),
            includeMedia = exportWithMedia
        )

        val dialog = AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING, themeResourcesProvider).apply {
            setTitle(getString(R.string.ExportChatProgressTitle))
            setMessage(LocaleController.formatString(R.string.ExportChatLoadingMessages, 0))
            setProgress(5)
            setCanceledOnTouchOutside(false)
            setCancelable(true)
            setNegativeButton(getString(R.string.Cancel)) { _, _ ->
                activeExporter?.cancel()
            }
            setOnCancelListener {
                activeExporter?.cancel()
            }
        }
        progressDialog = dialog
        showDialog(dialog)

        val exporter = ChatExportController(config, object : ChatExportController.Callback {
            override fun onProgress(stage: Int, current: Int, total: Int) {
                val dlg = progressDialog ?: return
                if (!dlg.isShowing) return
                when (stage) {
                    ChatExportController.STAGE_MESSAGES -> {
                        dlg.setMessage(LocaleController.formatString(R.string.ExportChatLoadingMessages, current))
                        val maxPct = if (exportWithMedia) 25 else 70
                        val ramped = 5 + ((maxPct - 5) * (1.0 - 1.0 / (1.0 + current / 400.0))).toInt()
                        dlg.setProgress(ramped.coerceIn(5, maxPct))
                    }
                    ChatExportController.STAGE_MEDIA -> {
                        dlg.setMessage(LocaleController.formatString(R.string.ExportChatDownloadingMedia, current, total))
                        if (total > 0) {
                            val pct = 25 + (current * 60f / total).toInt()
                            dlg.setProgress(pct.coerceIn(25, 85))
                        }
                    }
                    ChatExportController.STAGE_BUILDING -> {
                        dlg.setMessage(getString(R.string.ExportChatBuildingFile))
                        val pct = if (total > 0) {
                            85 + (current * 10f / total).toInt()
                        } else {
                            if (exportWithMedia) 90 else 85
                        }
                        dlg.setProgress(pct.coerceIn(85, 95))
                    }
                    ChatExportController.STAGE_SAVING -> {
                        dlg.setMessage(getString(R.string.ExportChatSavingFile))
                        dlg.setProgress(97)
                    }
                }
            }

            override fun onComplete(result: ChatExportController.ExportResult) {
                activeExporter = null
                dismissProgressDialog()
                showCompletionDialog(result)
            }

            override fun onEmpty() {
                activeExporter = null
                dismissProgressDialog()
                if (BulletinFactory.canShowBulletin(this@ChatExportActivity)) {
                    BulletinFactory.of(this@ChatExportActivity)
                        .createErrorBulletin(getString(R.string.ExportChatEmptyRange))
                        .show()
                }
            }

            override fun onError(message: String) {
                activeExporter = null
                dismissProgressDialog()
                if (BulletinFactory.canShowBulletin(this@ChatExportActivity)) {
                    BulletinFactory.of(this@ChatExportActivity)
                        .createErrorBulletin(LocaleController.formatString(R.string.ExportChatFailed, message))
                        .show()
                }
            }

            override fun onCancelled() {
                activeExporter = null
                dismissProgressDialog()
                if (BulletinFactory.canShowBulletin(this@ChatExportActivity)) {
                    BulletinFactory.of(this@ChatExportActivity)
                        .createSimpleBulletin(R.raw.info, getString(R.string.ExportChatCancelled))
                        .show()
                }
            }
        })
        activeExporter = exporter
        exporter.start()
    }

    private fun dismissProgressDialog() {
        progressDialog?.let {
            try {
                it.dismiss()
            } catch (_: Exception) {
            }
        }
        progressDialog = null
    }

    private fun showCompletionDialog(result: ChatExportController.ExportResult) {
        val activity = parentActivity ?: return

        val sizeStr = AndroidUtilities.formatFileSize(result.fileSizeBytes)
        val savedToDownloads = result.downloadsUri != null
        val summary = when {
            result.mediaCount > 0 && savedToDownloads -> LocaleController.formatString(
                R.string.ExportChatDoneSummaryMedia,
                result.messageCount, result.mediaCount, result.fileName, sizeStr
            )
            result.mediaCount > 0 -> LocaleController.formatString(
                R.string.ExportChatDoneSummaryMediaLocal,
                result.messageCount, result.mediaCount, result.fileName, sizeStr
            )
            savedToDownloads -> LocaleController.formatString(
                R.string.ExportChatDoneSummary,
                result.messageCount, result.fileName, sizeStr
            )
            else -> LocaleController.formatString(
                R.string.ExportChatDoneSummaryLocal,
                result.messageCount, result.fileName, sizeStr
            )
        }

        val builder = AlertDialog.Builder(activity, themeResourcesProvider).apply {
            setTitle(getString(R.string.ExportChatDoneTitle))
            setMessage(summary)
            setPositiveButton(getString(R.string.ExportChatShare)) { _, _ -> shareExportResult(result) }
            setNeutralButton(getString(R.string.ExportChatOpen)) { _, _ -> openExportResult(result) }
            setNegativeButton(getString(R.string.Done), null)
        }
        showDialog(builder.create())
    }

    private fun resolveShareUri(activity: Activity, result: ChatExportController.ExportResult): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 24) {
                FileProvider.getUriForFile(activity, "${ApplicationLoader.getApplicationId()}.provider", result.shareFile)
            } else {
                Uri.fromFile(result.shareFile)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
            result.downloadsUri
        }
    }

    private fun shareExportResult(result: ChatExportController.ExportResult) {
        val activity = parentActivity ?: return
        val uri = resolveShareUri(activity, result) ?: return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                clipData = ClipData.newRawUri(result.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(Intent.createChooser(intent, getString(R.string.ExportChatShare)), 500)
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    private fun openExportResult(result: ChatExportController.ExportResult) {
        val activity = parentActivity ?: return
        val uri = resolveShareUri(activity, result) ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, result.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, getString(R.string.ExportChatOpen)))
        } catch (t: Throwable) {
            FileLog.e(t)
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.ExportChatOpenError)).show()
            }
        }
    }

    // ==================== Preset Chips Cell ====================

    class PresetChipsCell(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : HorizontalScrollView(context) {

        private val chipViews = ArrayList<TextView>(4)
        private var needDivider: Boolean = false
        private var onPresetSelected: Utilities.Callback<Int>? = null

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            setWillNotDraw(false)

            val chipsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            }
            addView(chipsRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat()))

            val titles = intArrayOf(
                R.string.ExportChatAllTime,
                R.string.ExportChatLast7Days,
                R.string.ExportChatLast30Days,
                R.string.ExportChatThisMonth
            )
            titles.forEachIndexed { presetId, titleRes ->
                val chip = TextView(context).apply {
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                    typeface = AndroidUtilities.bold()
                    setPadding(dp(14f), 0, dp(14f), 0)
                    text = getString(titleRes)
                    setOnClickListener { onPresetSelected?.run(presetId) }
                }
                chipViews.add(chip)
                val lp = LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, dp(34f))
                if (presetId > 0) lp.leftMargin = dp(8f)
                chipsRow.addView(chip, lp)
            }
        }

        fun bind(activePreset: Int, divider: Boolean, listener: Utilities.Callback<Int>?) {
            needDivider = divider
            onPresetSelected = listener

            val activeBg = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
            val activePressed = Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)
            val activeText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider)
            val grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider)
            val inactiveBg = Theme.multAlpha(grayText, 0.14f)
            val inactivePressed = Theme.multAlpha(grayText, 0.24f)
            val inactiveText = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)

            chipViews.forEachIndexed { i, chip ->
                val selected = (i == activePreset)
                chip.setTextColor(if (selected) activeText else inactiveText)
                chip.background = Theme.createSimpleSelectorRoundRectDrawable(
                    dp(17f),
                    if (selected) activeBg else inactiveBg,
                    if (selected) activePressed else inactivePressed
                )
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(54f), MeasureSpec.EXACTLY)
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

        class Factory : UItem.UItemFactory<PresetChipsCell>() {
            override fun createView(
                context: Context,
                listView: RecyclerListView,
                currentAccount: Int,
                classGuid: Int,
                resourcesProvider: Theme.ResourcesProvider?
            ): PresetChipsCell = PresetChipsCell(context, resourcesProvider)

            override fun bindView(
                view: View,
                item: UItem,
                divider: Boolean,
                adapter: UniversalAdapter,
                listView: UniversalRecyclerView
            ) {
                (view as PresetChipsCell).bind(item.intValue, divider, item.intCallback)
            }

            override fun isClickable(): Boolean = false

            override fun equals(a: UItem, b: UItem): Boolean = a.id == b.id

            override fun contentsEquals(a: UItem, b: UItem): Boolean = a.intValue == b.intValue

            companion object {
                init {
                    setup(Factory())
                }

                fun of(id: Int, activePreset: Int, onSelected: Utilities.Callback<Int>): UItem {
                    return UItem.ofFactory(Factory::class.java).apply {
                        this.id = id
                        this.intValue = activePreset
                        this.intCallback = onSelected
                    }
                }
            }
        }
    }

    // ==================== Interactive Calendar Range Cell ====================

    internal interface CalendarListener {
        fun onPrevMonth()
        fun onNextMonth()
        fun onMonthTitleClick()
        fun onDayClick(dayKey: Int)
        fun onResetClick()
    }

    internal data class CalendarState(
        val year: Int,
        val month: Int,
        val todayKey: Int,
        val todayYear: Int,
        val todayMonth: Int,
        val startKey: Int,
        val endKey: Int,
        val summaryLabel: String
    )

    class CalendarRangeCell(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : LinearLayout(context) {

        private val prevButton: ImageView
        private val nextButton: ImageView
        private val monthTitleView: TextView
        private val gridView: CalendarMonthGridView
        private val summaryTextView: TextView
        private val resetTextView: TextView
        private var listener: CalendarListener? = null

        init {
            orientation = VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(10f))

            // Top bar: <   Month Year ▾   >
            val headerBar = FrameLayout(context)

            prevButton = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_ab_back)
                setOnClickListener { listener?.onPrevMonth() }
            }
            headerBar.addView(prevButton, LayoutHelper.createFrame(40, 40f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))

            nextButton = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_ab_back)
                rotation = 180f
                setOnClickListener { listener?.onNextMonth() }
            }
            headerBar.addView(nextButton, LayoutHelper.createFrame(40, 40f, Gravity.RIGHT or Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

            monthTitleView = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = AndroidUtilities.bold()
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                setOnClickListener { listener?.onMonthTitleClick() }
            }
            headerBar.addView(monthTitleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
            addView(headerBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))

            // Calendar month grid
            gridView = CalendarMonthGridView(context, resourcesProvider).apply {
                onDayClickListener = { dayKey -> listener?.onDayClick(dayKey) }
            }
            addView(gridView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 2f, 4f, 4f))

            // Selection summary footer
            val footer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
            }

            summaryTextView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                typeface = AndroidUtilities.bold()
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            footer.addView(summaryTextView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

            resetTextView = TextView(context).apply {
                text = getString(R.string.ExportChatResetRange)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                typeface = AndroidUtilities.bold()
                setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                setOnClickListener { listener?.onResetClick() }
            }
            footer.addView(resetTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        private fun bind(state: CalendarState, listener: CalendarListener?) {
            this.listener = listener

            val textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
            val accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourcesProvider)
            val selector = Theme.getColor(Theme.key_listSelector, resourcesProvider)

            val locale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()
            val rawMonths = DateFormatSymbols(locale).months
            var monthName = rawMonths.getOrNull(state.month) ?: ""
            if (monthName.isNotEmpty()) {
                monthName = monthName.substring(0, 1).uppercase(locale) + monthName.substring(1)
            }
            monthTitleView.text = "$monthName ${state.year} ▾"
            monthTitleView.setTextColor(textColor)
            monthTitleView.background = Theme.createSimpleSelectorRoundRectDrawable(dp(16f), Color.TRANSPARENT, selector)

            val canPrev = state.year > MIN_CALENDAR_YEAR || state.month > 0
            prevButton.setColorFilter(if (canPrev) textColor else Theme.multAlpha(textColor, 0.25f))
            prevButton.isEnabled = canPrev
            prevButton.background = Theme.createSelectorDrawable(selector, Theme.RIPPLE_MASK_CIRCLE_20DP)

            val canNext = state.year < state.todayYear || (state.year == state.todayYear && state.month < state.todayMonth)
            nextButton.setColorFilter(if (canNext) textColor else Theme.multAlpha(textColor, 0.25f))
            nextButton.isEnabled = canNext
            nextButton.background = Theme.createSelectorDrawable(selector, Theme.RIPPLE_MASK_CIRCLE_20DP)

            summaryTextView.text = state.summaryLabel
            summaryTextView.setTextColor(
                if (state.startKey != 0) accentColor else Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider)
            )

            resetTextView.setTextColor(accentColor)
            resetTextView.visibility = if (state.startKey != 0) VISIBLE else GONE

            gridView.setState(state)
        }

        class Factory : UItem.UItemFactory<CalendarRangeCell>() {
            override fun createView(
                context: Context,
                listView: RecyclerListView,
                currentAccount: Int,
                classGuid: Int,
                resourcesProvider: Theme.ResourcesProvider?
            ): CalendarRangeCell = CalendarRangeCell(context, resourcesProvider)

            override fun bindView(
                view: View,
                item: UItem,
                divider: Boolean,
                adapter: UniversalAdapter,
                listView: UniversalRecyclerView
            ) {
                val state = item.`object` as? CalendarState ?: return
                val listener = item.object2 as? CalendarListener
                (view as CalendarRangeCell).bind(state, listener)
            }

            override fun isClickable(): Boolean = false

            override fun equals(a: UItem, b: UItem): Boolean = a.id == b.id

            override fun contentsEquals(a: UItem, b: UItem): Boolean = a.`object` == b.`object`

            companion object {
                init {
                    setup(Factory())
                }

                internal fun of(id: Int, state: CalendarState, listener: CalendarListener): UItem {
                    return UItem.ofFactory(Factory::class.java).apply {
                        this.id = id
                        this.`object` = state
                        this.object2 = listener
                    }
                }
            }
        }
    }

    private class CalendarMonthGridView(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : View(context) {

        private val weekdayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(11.5f).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = AndroidUtilities.bold()
        }
        private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(15f).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val daySelectedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(15f).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = AndroidUtilities.bold()
        }
        private val rangeStripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selectedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val todayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f).toFloat()
        }
        private val stripRect = RectF()
        private val weekdays = arrayOf(
            getString(R.string.CalendarWeekNameShortMonday),
            getString(R.string.CalendarWeekNameShortTuesday),
            getString(R.string.CalendarWeekNameShortWednesday),
            getString(R.string.CalendarWeekNameShortThursday),
            getString(R.string.CalendarWeekNameShortFriday),
            getString(R.string.CalendarWeekNameShortSaturday),
            getString(R.string.CalendarWeekNameShortSunday)
        )

        private var state: CalendarState? = null
        private var daysInMonth: Int = 31
        private var firstDayOffset: Int = 0 // 0 = Monday .. 6 = Sunday
        private var rowCount: Int = 6
        private var downDayKey: Int = 0
        private var downX: Float = 0f
        private var downY: Float = 0f

        var onDayClickListener: ((Int) -> Unit)? = null

        fun setState(newState: CalendarState) {
            state = newState
            val cal = Calendar.getInstance().apply {
                clear()
                set(newState.year, newState.month, 1)
            }
            daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            firstDayOffset = if (dow == Calendar.SUNDAY) 6 else (dow - Calendar.MONDAY)
            val newRowCount = ceil((firstDayOffset + daysInMonth) / 7.0).toInt()
            if (newRowCount != rowCount) {
                rowCount = newRowCount
                requestLayout()
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = dp(28f) + rowCount * dp(42f) + dp(4f)
            setMeasuredDimension(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val st = state ?: return
            if (width == 0) return

            val textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
            val grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider)
            val accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
            val accentText = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider)

            weekdayPaint.color = grayColor
            daySelectedPaint.color = accentText
            selectedCirclePaint.color = accentColor
            rangeStripPaint.color = ColorUtils.setAlphaComponent(accentColor, (255 * 0.18f).toInt())
            todayRingPaint.color = accentColor

            val colW = width / 7f
            val headerH = dp(28f).toFloat()
            val rowH = dp(42f).toFloat()
            val circleR = dp(17f).toFloat()

            // Draw weekday headers
            val headerBaseline = headerH / 2f - (weekdayPaint.ascent() + weekdayPaint.descent()) / 2f
            for (c in 0 until 7) {
                canvas.drawText(weekdays[c], colW * c + colW / 2f, headerBaseline, weekdayPaint)
            }

            // Pass 1: draw continuous range strips per row
            if (st.startKey != 0 && st.endKey != 0 && st.startKey != st.endKey) {
                for (r in 0 until rowCount) {
                    var rowFirstCol = -1
                    var rowLastCol = -1
                    var rowStartsRange = false
                    var rowEndsRange = false

                    for (c in 0 until 7) {
                        val dayNum = r * 7 + c - firstDayOffset + 1
                        if (dayNum < 1 || dayNum > daysInMonth) continue
                        val key = toDayKey(st.year, st.month, dayNum)
                        if (key in st.startKey..st.endKey) {
                            if (rowFirstCol == -1) {
                                rowFirstCol = c
                                rowStartsRange = (key == st.startKey)
                            }
                            rowLastCol = c
                            rowEndsRange = (key == st.endKey)
                        }
                    }

                    if (rowFirstCol != -1) {
                        val cy = headerH + r * rowH + rowH / 2f
                        val left = if (rowStartsRange) {
                            colW * rowFirstCol + colW / 2f
                        } else {
                            colW * rowFirstCol + dp(4f)
                        }
                        val right = if (rowEndsRange) {
                            colW * rowLastCol + colW / 2f
                        } else {
                            colW * (rowLastCol + 1) - dp(4f)
                        }
                        if (right > left) {
                            stripRect.set(left, cy - circleR, right, cy + circleR)
                            canvas.drawRoundRect(stripRect, circleR, circleR, rangeStripPaint)
                        }
                    }
                }
            }

            // Pass 2: draw day circles and numbers
            val textOffsetY = -(dayPaint.ascent() + dayPaint.descent()) / 2f
            for (dayNum in 1..daysInMonth) {
                val idx = firstDayOffset + dayNum - 1
                val r = idx / 7
                val c = idx % 7
                val cx = colW * c + colW / 2f
                val cy = headerH + r * rowH + rowH / 2f

                val key = toDayKey(st.year, st.month, dayNum)
                val isFuture = key > st.todayKey
                val isToday = key == st.todayKey
                val isEndpoint = st.startKey != 0 && (key == st.startKey || key == st.endKey)

                if (isEndpoint) {
                    canvas.drawCircle(cx, cy, circleR, selectedCirclePaint)
                    canvas.drawText(dayNum.toString(), cx, cy + textOffsetY, daySelectedPaint)
                } else {
                    if (isToday) {
                        canvas.drawCircle(cx, cy, circleR - dp(1f), todayRingPaint)
                    }
                    dayPaint.color = if (isFuture) Theme.multAlpha(textColor, 0.28f) else textColor
                    canvas.drawText(dayNum.toString(), cx, cy + textOffsetY, dayPaint)
                }
            }
        }

        private fun hitTestDayKey(x: Float, y: Float): Int {
            val st = state ?: return 0
            if (width == 0) return 0
            val headerH = dp(28f).toFloat()
            val rowH = dp(42f).toFloat()
            if (y < headerH) return 0
            val r = ((y - headerH) / rowH).toInt()
            val c = (x / (width / 7f)).toInt()
            if (r !in 0 until rowCount || c !in 0 until 7) return 0
            val dayNum = r * 7 + c - firstDayOffset + 1
            if (dayNum !in 1..daysInMonth) return 0
            val key = toDayKey(st.year, st.month, dayNum)
            return if (key <= st.todayKey) key else 0
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downDayKey = hitTestDayKey(downX, downY)
                    return downDayKey != 0
                }
                MotionEvent.ACTION_UP -> {
                    if (downDayKey != 0) {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (dx < dp(20f) && dy < dp(20f)) {
                            val upKey = hitTestDayKey(event.x, event.y)
                            if (upKey == downDayKey) {
                                try {
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                } catch (_: Exception) {
                                }
                                onDayClickListener?.invoke(upKey)
                            }
                        }
                        downDayKey = 0
                        return true
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    downDayKey = 0
                }
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        private const val ID_PRESETS = 1
        private const val ID_CALENDAR = 2
        private const val ID_INCLUDE_MEDIA = 3

        private const val PRESET_NONE = -1
        private const val PRESET_ALL_TIME = 0
        private const val PRESET_7_DAYS = 1
        private const val PRESET_30_DAYS = 2
        private const val PRESET_THIS_MONTH = 3

        private const val MIN_CALENDAR_YEAR = 2013

        @JvmStatic
        fun openForChat(chatActivity: ChatActivity?) {
            if (chatActivity == null || chatActivity.parentActivity == null) return

            val dialogId = chatActivity.dialogId
            val mergeDialogId = chatActivity.mergeDialogId
            val topicId = chatActivity.topicId
            val isTopic = chatActivity.isTopic
            val isSavedMode = chatActivity.chatMode == ChatActivity.MODE_SAVED
            val currentChat = chatActivity.currentChat
            val currentUser = chatActivity.currentUser

            var title = ""
            var subtitle = ""
            if (isTopic && currentChat != null && topicId != 0L) {
                val topic = chatActivity.messagesController.topicsController.findTopic(currentChat.id, topicId)
                if (topic != null && !topic.title.isNullOrEmpty()) {
                    title = topic.title
                    subtitle = currentChat.title ?: ""
                }
            }
            if (title.isEmpty()) {
                when {
                    currentChat != null -> {
                        title = currentChat.title ?: ""
                        val username = ChatObject.getPublicUsername(currentChat)
                        if (!username.isNullOrEmpty()) {
                            subtitle = "@$username"
                        }
                    }
                    currentUser != null -> {
                        if (currentUser.self) {
                            title = getString(R.string.SavedMessages)
                        } else {
                            title = ContactsController.formatName(currentUser.first_name, currentUser.last_name) ?: ""
                            val username = UserObject.getPublicUsername(currentUser)
                            if (!username.isNullOrEmpty()) {
                                subtitle = "@$username"
                            }
                        }
                    }
                }
            }

            chatActivity.presentFragment(
                ChatExportActivity(
                    dialogId = dialogId,
                    mergeDialogId = mergeDialogId,
                    topicId = topicId,
                    isTopic = isTopic,
                    isSavedMode = isSavedMode,
                    chatTitle = title,
                    chatSubtitle = subtitle,
                    themeResourcesProvider = chatActivity.resourceProvider
                )
            )
        }

        private fun toDayKey(year: Int, month0Based: Int, dayOfMonth: Int): Int {
            return year * 10000 + (month0Based + 1) * 100 + dayOfMonth
        }

        private fun dayKeyToCalendar(dayKey: Int, endOfDay: Boolean): Calendar {
            val year = dayKey / 10000
            val month0 = ((dayKey / 100) % 100) - 1
            val day = dayKey % 100
            return Calendar.getInstance().apply {
                clear()
                if (endOfDay) {
                    set(year, month0, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                } else {
                    set(year, month0, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
        }
    }
}
