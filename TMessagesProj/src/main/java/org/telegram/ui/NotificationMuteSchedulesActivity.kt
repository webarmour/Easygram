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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ContactsController
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationMuteScheduleController
import org.telegram.messenger.NotificationMuteScheduleController.DialogSchedule
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeBind
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.Switch
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

class NotificationMuteSchedulesActivity : BaseFragment(), NotificationCenter.NotificationCenterDelegate {

    private var listView: UniversalRecyclerView? = null
    private var schedules: ArrayList<DialogSchedule> = ArrayList()
    private var peerLoadGeneration: Int = 0

    override fun useSimpleActionBar(): Boolean = true

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun getListViewForSimpleGlass(): RecyclerListView? = listView

    override fun onFragmentCreate(): Boolean {
        notificationCenter.addWeakObserver(this, NotificationCenter.notificationsSettingsUpdated)
        reloadSchedules()
        loadMissingPeers()
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        notificationCenter.removeObserver(this, NotificationCenter.notificationsSettingsUpdated)
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        reloadSchedules()
        loadMissingPeers()
        listView?.adapter?.update(true)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.notificationsSettingsUpdated) {
            reloadSchedules()
            listView?.adapter?.update(true)
        }
    }

    private fun reloadSchedules() {
        schedules = NotificationMuteScheduleController.getInstance(currentAccount).getAllSchedules()
    }

    private fun loadMissingPeers() {
        val missingEncryptedChats = ArrayList<Int>()
        val missingChats = ArrayList<Long>()
        val missingUsers = ArrayList<Long>()
        for (schedule in schedules) {
            val did = schedule.dialogId
            when {
                DialogObject.isEncryptedDialog(did) -> {
                    val encId = DialogObject.getEncryptedChatId(did)
                    val encChat = messagesController.getEncryptedChat(encId)
                    if (encChat == null) {
                        missingEncryptedChats.add(encId)
                    } else if (messagesController.getUser(encChat.user_id) == null) {
                        missingUsers.add(encChat.user_id)
                    }
                }
                DialogObject.isChatDialog(did) -> {
                    if (messagesController.getChat(-did) == null) {
                        missingChats.add(-did)
                    }
                }
                DialogObject.isUserDialog(did) -> {
                    if (messagesController.getUser(did) == null) {
                        missingUsers.add(did)
                    }
                }
            }
        }
        if (missingEncryptedChats.isEmpty() && missingChats.isEmpty() && missingUsers.isEmpty()) return

        messagesStorage.storageQueue.postRunnable {
            var loaded = false
            for (encId in missingEncryptedChats) {
                val encChat = messagesStorage.getEncryptedChat(encId.toLong())
                if (encChat != null) {
                    messagesController.putEncryptedChat(encChat, true)
                    loaded = true
                    if (messagesController.getUser(encChat.user_id) == null) {
                        messagesStorage.getUser(encChat.user_id)?.let {
                            messagesController.putUser(it, true)
                        }
                    }
                }
            }
            for (chatId in missingChats) {
                val chat = messagesStorage.getChat(chatId)
                if (chat != null) {
                    messagesController.putChat(chat, true)
                    loaded = true
                }
            }
            for (userId in missingUsers) {
                val user = messagesStorage.getUser(userId)
                if (user != null) {
                    messagesController.putUser(user, true)
                    loaded = true
                }
            }
            if (loaded) {
                AndroidUtilities.runOnUIThread {
                    peerLoadGeneration++
                    listView?.adapter?.update(true)
                }
            }
        }
    }

    override fun createView(context: Context): View {
        setHasOwnBackground(true)
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back)
        simpleActionBar.setTitle(getString(R.string.NotificationMuteScheduleTitle))
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
            { item, view, _, x, _ -> onItemClick(item, view, x) },
            { item, _, _, _, _ -> onItemLongClick(item) }
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

    private fun fillItems(items: ArrayList<UItem>, @Suppress("UNUSED_PARAMETER") adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(ID_ADD_CHATS, R.drawable.menu_premium_clock_add, getString(R.string.NotificationMuteScheduleAddChats))
                .accent()
        )
        if (schedules.isNotEmpty()) {
            items.add(UItem.asShadow(-100, null))
            items.add(UItem.asHeader(getString(R.string.NotificationMuteScheduleTitle)))
            val now = System.currentTimeMillis()
            schedules.forEachIndexed { index, schedule ->
                items.add(DialogScheduleCell.Factory.of(index, schedule, peerLoadGeneration, now))
            }
        }
        items.add(UItem.asShadow(-101, getString(R.string.NotificationMuteScheduleListInfo)))
    }

    private fun onItemClick(item: UItem, view: View, x: Float) {
        if (item.id == ID_ADD_CHATS) {
            val selectActivity = UsersSelectActivity(true, ArrayList(), 0).apply {
                noChatTypes = true
                allowBots = true
                allowSelf = false
                customTitle = getString(R.string.NotificationMuteScheduleSelectChats)
                setDelegate { ids, _ ->
                    if (ids.isNullOrEmpty()) return@setDelegate
                    if (ids.size == 1) {
                        presentFragment(NotificationMuteScheduleActivity(ids[0], 0L, resourceProvider))
                    } else {
                        presentFragment(NotificationMuteScheduleActivity(ids, resourceProvider))
                    }
                }
            }
            presentFragment(selectActivity)
            return
        }

        val schedule = item.`object` as? DialogSchedule ?: return
        val switchClick = if (LocaleController.isRTL) x <= dp(76f) else x >= view.measuredWidth - dp(76f)
        if (switchClick) {
            val newEnabled = !schedule.enabled
            NotificationMuteScheduleController.getInstance(currentAccount)
                .setScheduleEnabled(schedule.dialogId, schedule.topicId, newEnabled)
            reloadSchedules()
            if (view is DialogScheduleCell) {
                view.setChecked(newEnabled, true)
            }
            listView?.adapter?.update(true)
        } else {
            presentFragment(NotificationMuteScheduleActivity(schedule.dialogId, schedule.topicId, resourceProvider))
        }
    }

    private fun onItemLongClick(item: UItem): Boolean {
        val schedule = item.`object` as? DialogSchedule ?: return false
        val activity = parentActivity ?: return false
        val dialog = AlertDialog.Builder(activity, resourceProvider)
            .setTitle(getString(R.string.NotificationMuteScheduleDeleteConfirmTitle))
            .setMessage(getString(R.string.NotificationMuteScheduleDeleteConfirm))
            .setPositiveButton(getString(R.string.Delete)) { _, _ ->
                NotificationMuteScheduleController.getInstance(currentAccount)
                    .removeSchedule(schedule.dialogId, schedule.topicId)
                reloadSchedules()
                listView?.adapter?.update(true)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .create()
        showDialog(dialog)
        (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)?.let { button ->
            ThemeBind.textColor(button, Theme.key_text_RedBold, resourceProvider)
        }
        return true
    }

    class DialogScheduleCell(
        context: Context,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : FrameLayout(context) {

        private val avatarImageView: BackupImageView
        private val avatarDrawable = AvatarDrawable()
        private val titleTextView: TextView
        private val subtitleTextView: TextView
        private val checkBox: Switch
        private var needDivider: Boolean = false

        init {
            setWillNotDraw(false)

            avatarImageView = BackupImageView(context).apply {
                setRoundRadius(dp(20f))
            }
            addView(
                avatarImageView,
                LayoutHelper.createFrame(
                    40,
                    40f,
                    (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL,
                    if (LocaleController.isRTL) 0f else 16f,
                    0f,
                    if (LocaleController.isRTL) 16f else 0f,
                    0f
                )
            )

            titleTextView = TextView(context).apply {
                ThemeBind.textColor(this, Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                setLines(1)
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
            }
            addView(
                titleTextView,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT.toFloat(),
                    (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP,
                    if (LocaleController.isRTL) 80f else 68f,
                    11f,
                    if (LocaleController.isRTL) 68f else 80f,
                    0f
                )
            )

            subtitleTextView = TextView(context).apply {
                ThemeBind.textColor(this, Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setLines(1)
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
            }
            addView(
                subtitleTextView,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT.toFloat(),
                    (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP,
                    if (LocaleController.isRTL) 80f else 68f,
                    35f,
                    if (LocaleController.isRTL) 68f else 80f,
                    0f
                )
            )

            checkBox = Switch(context, resourcesProvider).apply {
                setColors(
                    Theme.key_switchTrack,
                    Theme.key_switchTrackChecked,
                    Theme.key_windowBackgroundWhite,
                    Theme.key_windowBackgroundWhite
                )
                isFocusable = false
            }
            addView(
                checkBox,
                LayoutHelper.createFrame(
                    37,
                    40f,
                    (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL,
                    21f,
                    0f,
                    21f,
                    0f
                )
            )
        }

        fun setChecked(checked: Boolean, animated: Boolean) {
            checkBox.setChecked(checked, animated)
        }

        fun bind(currentAccount: Int, schedule: DialogSchedule, divider: Boolean) {
            needDivider = divider
            val mc = MessagesController.getInstance(currentAccount)
            val title: CharSequence = when {
                DialogObject.isEncryptedDialog(schedule.dialogId) -> {
                    val encChat = mc.getEncryptedChat(DialogObject.getEncryptedChatId(schedule.dialogId))
                    val user = encChat?.let { mc.getUser(it.user_id) }
                    if (user != null) {
                        avatarDrawable.setInfo(currentAccount, user)
                        avatarImageView.setForUserOrChat(user, avatarDrawable)
                        ContactsController.formatName(user.first_name, user.last_name) ?: schedule.dialogId.toString()
                    } else {
                        avatarDrawable.setInfo(schedule.dialogId, "?", null)
                        avatarImageView.setImageDrawable(avatarDrawable)
                        schedule.dialogId.toString()
                    }
                }
                DialogObject.isChatDialog(schedule.dialogId) -> {
                    val chat = mc.getChat(-schedule.dialogId)
                    if (schedule.topicId != 0L) {
                        val topic = mc.topicsController.findTopic(-schedule.dialogId, schedule.topicId)
                        when {
                            topic != null -> {
                                ForumUtilities.setTopicIcon(avatarImageView, topic, false, true, resourcesProvider)
                                if (chat != null && !chat.title.isNullOrEmpty()) {
                                    "${chat.title} · ${topic.title}"
                                } else {
                                    topic.title ?: ""
                                }
                            }
                            chat != null -> {
                                avatarDrawable.setInfo(currentAccount, chat)
                                avatarImageView.setForUserOrChat(chat, avatarDrawable)
                                chat.title ?: ""
                            }
                            else -> {
                                avatarDrawable.setInfo(schedule.dialogId, "?", null)
                                avatarImageView.setImageDrawable(avatarDrawable)
                                schedule.dialogId.toString()
                            }
                        }
                    } else if (chat != null) {
                        avatarDrawable.setInfo(currentAccount, chat)
                        avatarImageView.setForUserOrChat(chat, avatarDrawable)
                        chat.title ?: ""
                    } else {
                        avatarDrawable.setInfo(schedule.dialogId, "?", null)
                        avatarImageView.setImageDrawable(avatarDrawable)
                        schedule.dialogId.toString()
                    }
                }
                else -> {
                    val user = mc.getUser(schedule.dialogId)
                    if (user != null) {
                        avatarDrawable.setInfo(currentAccount, user)
                        avatarImageView.setForUserOrChat(user, avatarDrawable)
                        ContactsController.formatName(user.first_name, user.last_name) ?: schedule.dialogId.toString()
                    } else {
                        avatarDrawable.setInfo(schedule.dialogId, "?", null)
                        avatarImageView.setImageDrawable(avatarDrawable)
                        schedule.dialogId.toString()
                    }
                }
            }
            titleTextView.text = title

            val now = System.currentTimeMillis()
            val mutedNow = schedule.enabled && schedule.isEffectivelyMutedNow(now)
            val summary = if (schedule.enabled) {
                schedule.formatSummary()
            } else {
                "${getString(R.string.NotificationMuteScheduleOff)} · ${schedule.formatSummary()}"
            }

            if (mutedNow) {
                val badge = getString(R.string.NotificationMuteScheduleMutedNowBadge)
                val ssb = SpannableStringBuilder("$badge · $summary")
                ssb.setSpan(
                    ForegroundColorSpan(Theme.getColor(Theme.key_text_RedBold, resourcesProvider)),
                    0,
                    badge.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                subtitleTextView.text = ssb
            } else {
                subtitleTextView.text = summary
            }

            checkBox.setChecked(schedule.enabled, false)
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(64f), MeasureSpec.EXACTLY)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = (if (LocaleController.isRTL) dp(76f) else measuredWidth - dp(76f)).toFloat()
            canvas.drawLine(x, dp(18f).toFloat(), x, (measuredHeight - dp(18f)).toFloat(), Theme.dividerPaint)
            if (needDivider) {
                canvas.drawLine(
                    if (LocaleController.isRTL) 0f else dp(68f).toFloat(),
                    (measuredHeight - 1).toFloat(),
                    (measuredWidth - if (LocaleController.isRTL) dp(68f) else 0).toFloat(),
                    (measuredHeight - 1).toFloat(),
                    Theme.dividerPaint
                )
            }
        }

        class Factory : UItem.UItemFactory<DialogScheduleCell>() {
            override fun createView(
                context: Context,
                listView: RecyclerListView,
                currentAccount: Int,
                classGuid: Int,
                resourcesProvider: Theme.ResourcesProvider?
            ): DialogScheduleCell = DialogScheduleCell(context, resourcesProvider)

            override fun bindView(
                view: View,
                item: UItem,
                divider: Boolean,
                adapter: UniversalAdapter,
                listView: UniversalRecyclerView
            ) {
                val schedule = item.`object` as? DialogSchedule ?: return
                (view as DialogScheduleCell).bind(adapter.currentAccount, schedule, divider)
            }

            override fun equals(a: UItem, b: UItem): Boolean {
                val sa = a.`object` as? DialogSchedule
                val sb = b.`object` as? DialogSchedule
                if (sa != null && sb != null) {
                    return sa.dialogId == sb.dialogId && sa.topicId == sb.topicId
                }
                return a.id == b.id
            }

            override fun contentsEquals(a: UItem, b: UItem): Boolean {
                val sa = a.`object` as? DialogSchedule ?: return false
                val sb = b.`object` as? DialogSchedule ?: return false
                return a.intValue == b.intValue &&
                    a.checked == b.checked &&
                    sa.enabled == sb.enabled &&
                    sa.formatSummary() == sb.formatSummary()
            }

            companion object {
                init {
                    setup(Factory())
                }

                fun of(id: Int, schedule: DialogSchedule, peerLoadGeneration: Int, nowMillis: Long): UItem {
                    return UItem.ofFactory(Factory::class.java).apply {
                        this.id = id
                        this.`object` = schedule
                        this.intValue = peerLoadGeneration
                        this.checked = schedule.enabled && schedule.isEffectivelyMutedNow(nowMillis)
                    }
                }
            }
        }
    }

    companion object {
        private const val ID_ADD_CHATS = -1
    }
}
