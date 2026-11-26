/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class NotificationMuteScheduleController(num: Int) : BaseController(num) {

    class MuteInterval @JvmOverloads constructor(
        daysMask: Int = DAYS_ALL,
        @JvmField var allDay: Boolean = false,
        startMinute: Int = 22 * 60,
        endMinute: Int = 8 * 60
    ) {
        @JvmField
        var daysMask: Int = daysMask and DAYS_ALL

        @JvmField
        var startMinute: Int = Utilities.clamp(startMinute, 24 * 60 - 1, 0)

        @JvmField
        var endMinute: Int = Utilities.clamp(endMinute, 24 * 60 - 1, 0)

        fun copy(): MuteInterval = MuteInterval(daysMask, allDay, startMinute, endMinute)

        fun isEffectivelyAllDay(): Boolean = allDay || startMinute == endMinute

        fun isOvernight(): Boolean = !allDay && startMinute > endMinute

        fun isActiveAt(dayIdx: Int, minuteOfDay: Int): Boolean {
            if (daysMask == 0) return false
            if (isEffectivelyAllDay()) {
                return (daysMask and (1 shl dayIdx)) != 0
            }
            return if (startMinute < endMinute) {
                (daysMask and (1 shl dayIdx)) != 0 && minuteOfDay in startMinute until endMinute
            } else {
                val prevDayIdx = (dayIdx + 6) % 7
                val eveningPart = (daysMask and (1 shl dayIdx)) != 0 && minuteOfDay >= startMinute
                val morningPart = (daysMask and (1 shl prevDayIdx)) != 0 && minuteOfDay < endMinute
                eveningPart || morningPart
            }
        }

        fun formatTimeRange(): String {
            if (isEffectivelyAllDay()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleAllDay)
            }
            return "${formatMinute(startMinute)} – ${formatMinute(endMinute)}"
        }

        fun formatSummary(): String = "${formatDays(daysMask)} · ${formatTimeRange()}"

        fun toJson(): JSONObject = JSONObject().apply {
            try {
                put("days", daysMask)
                put("allDay", allDay)
                put("start", startMinute)
                put("end", endMinute)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MuteInterval) return false
            return daysMask == other.daysMask &&
                allDay == other.allDay &&
                startMinute == other.startMinute &&
                endMinute == other.endMinute
        }

        override fun hashCode(): Int {
            var result = daysMask
            result = 31 * result + allDay.hashCode()
            result = 31 * result + startMinute
            result = 31 * result + endMinute
            return result
        }

        companion object {
            @JvmStatic
            fun fromJson(obj: JSONObject?): MuteInterval? {
                if (obj == null) return null
                val days = obj.optInt("days", DAYS_ALL) and DAYS_ALL
                val allDay = obj.optBoolean("allDay", false)
                val start = obj.optInt("start", 22 * 60)
                val end = obj.optInt("end", 8 * 60)
                return MuteInterval(days, allDay, start, end)
            }
        }
    }

    class DialogSchedule(
        @JvmField var dialogId: Long,
        @JvmField var topicId: Long
    ) {
        @JvmField
        var enabled: Boolean = true

        @JvmField
        val intervals: ArrayList<MuteInterval> = ArrayList()

        @JvmField
        var appliedMutedBySchedule: Boolean = false

        @JvmField
        var appliedMuteUntil: Int = 0

        @JvmField
        var skipUntilMillis: Long = 0L

        fun getKey(): String = NotificationsController.getSharedPrefKey(dialogId, topicId, true)

        fun copy(): DialogSchedule = DialogSchedule(dialogId, topicId).also { c ->
            c.enabled = enabled
            c.appliedMutedBySchedule = appliedMutedBySchedule
            c.appliedMuteUntil = appliedMuteUntil
            c.skipUntilMillis = skipUntilMillis
            for (interval in intervals) {
                c.intervals.add(interval.copy())
            }
        }

        fun hasValidIntervals(): Boolean = intervals.any { it.daysMask != 0 }

        fun isMutedAt(cal: Calendar): Boolean {
            if (!enabled || intervals.isEmpty()) return false
            val dayIdx = getDayIndex(cal)
            val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return intervals.any { it.isActiveAt(dayIdx, minuteOfDay) }
        }

        fun isMutedAtMillis(millis: Long): Boolean {
            val cal = Calendar.getInstance()
            cal.timeInMillis = millis
            return isMutedAt(cal)
        }

        fun isEffectivelyMutedNow(nowMillis: Long): Boolean {
            return isMutedAtMillis(nowMillis) && nowMillis >= skipUntilMillis
        }

        /**
         * Returns the exact wall-clock timestamp (ms) when the schedule's muted state next changes
         * (true -> false or false -> true), or -1 if the schedule never changes state (e.g. disabled,
         * no days selected, or muted 24/7).
         */
        fun getNextTransitionTimeMillis(nowMillis: Long): Long {
            if (!enabled || !hasValidIntervals()) return -1L
            val currentlyMuted = isMutedAtMillis(nowMillis)

            val dayStart = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val candidates = TreeSet<Long>()
            for (d in 0..8) {
                val dayCal = (dayStart.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, d)
                }
                val dayIdx = getDayIndex(dayCal)
                val prevDayIdx = (dayIdx + 6) % 7

                for (interval in intervals) {
                    if (interval.daysMask == 0) continue
                    when {
                        interval.isEffectivelyAllDay() -> {
                            if ((interval.daysMask and (1 shl dayIdx)) != 0) {
                                addCandidate(candidates, dayCal, 0, nowMillis)
                                val nextDay = (dayCal.clone() as Calendar).apply {
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }
                                addCandidate(candidates, nextDay, 0, nowMillis)
                            }
                        }
                        interval.startMinute < interval.endMinute -> {
                            if ((interval.daysMask and (1 shl dayIdx)) != 0) {
                                addCandidate(candidates, dayCal, interval.startMinute, nowMillis)
                                addCandidate(candidates, dayCal, interval.endMinute, nowMillis)
                            }
                        }
                        else -> {
                            if ((interval.daysMask and (1 shl dayIdx)) != 0) {
                                addCandidate(candidates, dayCal, interval.startMinute, nowMillis)
                            }
                            if ((interval.daysMask and (1 shl prevDayIdx)) != 0) {
                                addCandidate(candidates, dayCal, interval.endMinute, nowMillis)
                            }
                        }
                    }
                }
            }

            val probe = Calendar.getInstance()
            for (ts in candidates) {
                probe.timeInMillis = ts
                if (isMutedAt(probe) != currentlyMuted) {
                    return ts
                }
            }
            return -1L
        }

        fun formatSummary(): String {
            if (intervals.isEmpty()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleOff)
            }
            val first = intervals[0].formatSummary()
            if (intervals.size == 1) return first
            return "$first (${LocaleController.formatString(R.string.NotificationMuteScheduleMoreIntervals, intervals.size - 1)})"
        }

        fun formatStatusText(nowMillis: Long): String {
            if (!enabled) {
                return LocaleController.getString(R.string.NotificationMuteScheduleStatusDisabled)
            }
            if (!hasValidIntervals()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleNoDays)
            }
            val inWindow = isMutedAtMillis(nowMillis)
            val nextTransition = getNextTransitionTimeMillis(nowMillis)
            return if (inWindow) {
                if (nowMillis < skipUntilMillis) {
                    if (skipUntilMillis < Long.MAX_VALUE - 1000L) {
                        val nextStart = getNextTransitionTimeMillis(skipUntilMillis + 1000L)
                        if (nextStart > 0) {
                            return LocaleController.formatString(
                                R.string.NotificationMuteScheduleStatusManualUnmute,
                                formatTransitionTime(nextStart, nowMillis)
                            )
                        }
                    } else {
                        // Manually unmuted during a 24/7 schedule: do not falsely claim "Muted 24/7"
                        return LocaleController.getString(R.string.NotificationMuteScheduleStatusDisabled)
                    }
                }
                if (nextTransition < 0) {
                    LocaleController.getString(R.string.NotificationMuteScheduleStatusMuted247)
                } else {
                    LocaleController.formatString(
                        R.string.NotificationMuteScheduleStatusMutedUntil,
                        formatTransitionTime(nextTransition, nowMillis)
                    )
                }
            } else {
                if (nextTransition < 0) {
                    LocaleController.getString(R.string.NotificationMuteScheduleStatusDisabled)
                } else {
                    LocaleController.formatString(
                        R.string.NotificationMuteScheduleStatusActiveUntil,
                        formatTransitionTime(nextTransition, nowMillis)
                    )
                }
            }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            try {
                put("dialogId", dialogId)
                put("topicId", topicId)
                put("enabled", enabled)
                put("appliedMuted", appliedMutedBySchedule)
                put("appliedUntil", appliedMuteUntil)
                put("skipUntil", skipUntilMillis)
                val arr = JSONArray()
                for (interval in intervals) {
                    arr.put(interval.toJson())
                }
                put("intervals", arr)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        companion object {
            private fun addCandidate(candidates: TreeSet<Long>, dayCal: Calendar, minuteOfDay: Int, nowMillis: Long) {
                val c = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                    set(Calendar.MINUTE, minuteOfDay % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val millis = c.timeInMillis
                if (millis > nowMillis) {
                    candidates.add(millis)
                }
            }

            @JvmStatic
            fun fromJson(obj: JSONObject?): DialogSchedule? {
                if (obj == null) return null
                val dialogId = obj.optLong("dialogId", 0L)
                val topicId = obj.optLong("topicId", 0L)
                if (dialogId == 0L) return null
                return DialogSchedule(dialogId, topicId).apply {
                    enabled = obj.optBoolean("enabled", true)
                    appliedMutedBySchedule = obj.optBoolean("appliedMuted", false)
                    appliedMuteUntil = obj.optInt("appliedUntil", 0)
                    skipUntilMillis = obj.optLong("skipUntil", 0L)
                    val arr = obj.optJSONArray("intervals")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            MuteInterval.fromJson(arr.optJSONObject(i))?.let { intervals.add(it) }
                        }
                    }
                }
            }
        }
    }

    private data class PendingAction(
        val dialogId: Long,
        val topicId: Long,
        val mute: Boolean,
        val muteDurationSeconds: Int
    )

    private val lock = Any()
    private val schedules = LinkedHashMap<String, DialogSchedule>()
    private val preferences: SharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences(
        "notification_mute_schedules_$currentAccount",
        Activity.MODE_PRIVATE
    )
    private val inAppCheckRunnable = Runnable { checkSchedules(false, null) }

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        synchronized(lock) {
            schedules.clear()
            val json = preferences.getString(PREFS_KEY_SCHEDULES, null)
            if (json.isNullOrEmpty()) return
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    DialogSchedule.fromJson(arr.optJSONObject(i))?.let { s ->
                        schedules[s.getKey()] = s
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
    }

    private fun saveToPrefsLocked() {
        val arr = JSONArray()
        for (s in schedules.values) {
            arr.put(s.toJson())
        }
        preferences.edit().putString(PREFS_KEY_SCHEDULES, arr.toString()).apply()
    }

    fun getSchedule(dialogId: Long, topicId: Long): DialogSchedule? {
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        synchronized(lock) {
            return schedules[key]?.copy()
        }
    }

    fun getScheduleSummary(dialogId: Long, topicId: Long): String? {
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        synchronized(lock) {
            val s = schedules[key]
            if (s == null || !s.enabled || !s.hasValidIntervals()) return null
            return s.formatSummary()
        }
    }

    fun getAllSchedules(): ArrayList<DialogSchedule> {
        synchronized(lock) {
            val result = ArrayList<DialogSchedule>(schedules.size)
            for (s in schedules.values) {
                result.add(s.copy())
            }
            return result
        }
    }

    fun getEnabledSchedulesCount(): Int {
        synchronized(lock) {
            return schedules.values.count { it.enabled && it.hasValidIntervals() }
        }
    }

    /**
     * Fast check used by NotificationsController.getNotifyOverride() as defense-in-depth
     * in case an incoming push arrives during a muted interval before the server mute sync completed.
     */
    fun isDialogScheduledMutedNow(dialogId: Long, topicId: Long): Boolean {
        if (!userConfig.isClientActivated) return false
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        var mutedNow = false
        var needsSync = false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = schedules[key]
            if (s != null && s.enabled && s.hasValidIntervals()) {
                mutedNow = s.isEffectivelyMutedNow(now)
                if (mutedNow && !s.appliedMutedBySchedule) {
                    needsSync = true
                }
            }
        }
        if (needsSync) {
            AndroidUtilities.runOnUIThread { checkSchedules(true, null) }
        }
        return mutedNow
    }

    fun saveSchedule(updated: DialogSchedule) {
        saveSchedules(listOf(updated))
    }

    fun saveSchedules(updatedList: List<DialogSchedule>) {
        if (updatedList.isEmpty()) return
        val toUnmute = ArrayList<Pair<Long, Long>>()
        var anySaved = false
        synchronized(lock) {
            for (updated in updatedList) {
                if (updated.dialogId == 0L) continue
                val key = updated.getKey()
                val existing = schedules[key]
                val stored = updated.copy()
                if (existing != null) {
                    stored.appliedMutedBySchedule = existing.appliedMutedBySchedule
                    stored.appliedMuteUntil = existing.appliedMuteUntil
                }
                stored.skipUntilMillis = 0L
                if (!stored.enabled && stored.appliedMutedBySchedule) {
                    toUnmute.add(updated.dialogId to updated.topicId)
                    stored.appliedMutedBySchedule = false
                    stored.appliedMuteUntil = 0
                }
                schedules[key] = stored
                anySaved = true
            }
            if (anySaved) {
                saveToPrefsLocked()
            }
        }
        if (!anySaved) return
        for ((did, tid) in toUnmute) {
            applyUnmuteAsync(did, tid, null)
        }
        checkSchedules(false, null)
        AndroidUtilities.runOnUIThread {
            notificationCenter.postNotificationName(NotificationCenter.notificationsSettingsUpdated)
        }
    }

    fun setScheduleEnabled(dialogId: Long, topicId: Long, enabled: Boolean) {
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        var needUnmuteNow = false
        synchronized(lock) {
            val s = schedules[key] ?: return
            if (s.enabled == enabled) return
            s.enabled = enabled
            if (enabled) {
                s.skipUntilMillis = 0L
            } else if (s.appliedMutedBySchedule) {
                s.appliedMutedBySchedule = false
                s.appliedMuteUntil = 0
                needUnmuteNow = true
            }
            saveToPrefsLocked()
        }
        if (needUnmuteNow) {
            applyUnmuteAsync(dialogId, topicId, null)
        }
        checkSchedules(false, null)
        AndroidUtilities.runOnUIThread {
            notificationCenter.postNotificationName(NotificationCenter.notificationsSettingsUpdated)
        }
    }

    fun removeSchedule(dialogId: Long, topicId: Long) {
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        val needUnmuteNow: Boolean
        synchronized(lock) {
            val removed = schedules.remove(key) ?: return
            needUnmuteNow = removed.appliedMutedBySchedule
            saveToPrefsLocked()
        }
        if (needUnmuteNow) {
            applyUnmuteAsync(dialogId, topicId, null)
        }
        rescheduleTimersAndAlarms()
        AndroidUtilities.runOnUIThread {
            notificationCenter.postNotificationName(NotificationCenter.notificationsSettingsUpdated)
        }
    }

    /**
     * Called by NotificationsController when the user manually changes a dialog's mute state.
     * If the user manually unmutes during an active quiet window, we pause re-muting until the
     * end of the current quiet window so we don't fight the user's explicit action.
     */
    fun onManualMuteChange(dialogId: Long, topicId: Long, muted: Boolean) {
        if (isApplyingSchedule()) return
        val key = NotificationsController.getSharedPrefKey(dialogId, topicId, true)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = schedules[key] ?: return
            if (!s.enabled || !s.hasValidIntervals()) return
            if (!muted) {
                if (s.isMutedAtMillis(now)) {
                    val windowEnd = s.getNextTransitionTimeMillis(now)
                    s.skipUntilMillis = if (windowEnd > now) windowEnd else Long.MAX_VALUE
                }
                s.appliedMutedBySchedule = false
                s.appliedMuteUntil = 0
                saveToPrefsLocked()
            } else {
                if (s.skipUntilMillis > 0L && s.isMutedAtMillis(now)) {
                    s.skipUntilMillis = 0L
                    saveToPrefsLocked()
                }
            }
        }
    }

    fun checkSchedules(fromBackground: Boolean, onDone: Runnable?) {
        checkSchedulesInternal(fromBackground, rescheduleGlobalAlarm = true) {
            onDone?.run()
        }
    }

    internal fun checkSchedulesInternal(
        fromBackground: Boolean,
        rescheduleGlobalAlarm: Boolean,
        onDone: ((Boolean) -> Unit)?
    ) {
        if (!userConfig.isClientActivated) {
            onDone?.invoke(false)
            return
        }

        val nowMillis = System.currentTimeMillis()
        val actions = ArrayList<PendingAction>()
        var prefsDirty = false

        synchronized(lock) {
            for (s in schedules.values) {
                if (!s.enabled || !s.hasValidIntervals()) {
                    if (s.appliedMutedBySchedule) {
                        s.appliedMutedBySchedule = false
                        s.appliedMuteUntil = 0
                        prefsDirty = true
                        actions.add(PendingAction(s.dialogId, s.topicId, false, 0))
                    }
                    continue
                }

                if (s.skipUntilMillis > 0L && nowMillis >= s.skipUntilMillis) {
                    s.skipUntilMillis = 0L
                    prefsDirty = true
                }

                val inWindow = s.isMutedAtMillis(nowMillis)
                val shouldMute = inWindow && nowMillis >= s.skipUntilMillis

                if (shouldMute) {
                    val nextEndMillis = s.getNextTransitionTimeMillis(nowMillis)
                    val muteDurationSec: Int
                    val expectedServerUntilSec: Int
                    if (nextEndMillis < 0) {
                        muteDurationSec = Int.MAX_VALUE
                        expectedServerUntilSec = Int.MAX_VALUE
                    } else {
                        val remainingSec = max(1L, ceil((nextEndMillis - nowMillis) / 1000.0).toLong())
                        muteDurationSec = min((Int.MAX_VALUE - 1).toLong(), remainingSec).toInt()
                        expectedServerUntilSec = connectionsManager.currentTime + muteDurationSec
                    }

                    val alreadyMuted = messagesController.isDialogMuted(s.dialogId, s.topicId)
                    val sameWindow = s.appliedMutedBySchedule && alreadyMuted && (
                        (expectedServerUntilSec == Int.MAX_VALUE && s.appliedMuteUntil == Int.MAX_VALUE) ||
                            (expectedServerUntilSec != Int.MAX_VALUE && abs(s.appliedMuteUntil - expectedServerUntilSec) <= 90)
                        )

                    if (!sameWindow) {
                        s.appliedMutedBySchedule = true
                        s.appliedMuteUntil = expectedServerUntilSec
                        prefsDirty = true
                        actions.add(PendingAction(s.dialogId, s.topicId, true, muteDurationSec))
                    }
                } else {
                    if (s.appliedMutedBySchedule) {
                        s.appliedMutedBySchedule = false
                        s.appliedMuteUntil = 0
                        prefsDirty = true
                        actions.add(PendingAction(s.dialogId, s.topicId, false, 0))
                    }
                }
            }
            if (prefsDirty) {
                saveToPrefsLocked()
            }
        }

        rescheduleTimersAndAlarms(rescheduleGlobalAlarm)

        if (actions.isEmpty()) {
            onDone?.invoke(false)
            return
        }

        if (fromBackground) {
            connectionsManager.resumeNetworkMaybe()
        }

        val remaining = AtomicInteger(actions.size)
        val actionFinished = Runnable {
            if (remaining.decrementAndGet() == 0) {
                onDone?.invoke(true)
            }
        }

        for (action in actions) {
            if (action.mute) {
                applyMuteAsync(action.dialogId, action.topicId, action.muteDurationSeconds, actionFinished)
            } else {
                applyUnmuteAsync(action.dialogId, action.topicId, actionFinished)
            }
        }
    }

    private fun ensurePeerLoadedAndRun(dialogId: Long, uiAction: Runnable) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            AndroidUtilities.runOnUIThread(uiAction)
            return
        }
        val needChat = DialogObject.isChatDialog(dialogId) && messagesController.getChat(-dialogId) == null
        val needUser = DialogObject.isUserDialog(dialogId) &&
            dialogId != userConfig.clientUserId &&
            messagesController.getUser(dialogId) == null
        if (!needChat && !needUser) {
            AndroidUtilities.runOnUIThread(uiAction)
            return
        }
        messagesStorage.storageQueue.postRunnable {
            if (needChat) {
                messagesStorage.getChat(-dialogId)?.let { messagesController.putChat(it, true) }
            } else {
                messagesStorage.getUser(dialogId)?.let { messagesController.putUser(it, true) }
            }
            AndroidUtilities.runOnUIThread(uiAction)
        }
    }

    private fun applyMuteAsync(dialogId: Long, topicId: Long, durationSeconds: Int, onDone: Runnable?) {
        ensurePeerLoadedAndRun(dialogId) {
            enterApplying()
            try {
                notificationsController.muteUntil(dialogId, topicId, durationSeconds)
            } catch (e: Exception) {
                FileLog.e(e)
            } finally {
                exitApplying()
            }
            onDone?.run()
        }
    }

    private fun applyUnmuteAsync(dialogId: Long, topicId: Long, onDone: Runnable?) {
        ensurePeerLoadedAndRun(dialogId) {
            enterApplying()
            try {
                val prefs = MessagesController.getNotificationsSettings(currentAccount)
                val key = NotificationsController.getSharedPrefKey(dialogId, topicId)
                prefs.edit().remove("notifyuntil_$key").apply()
                notificationsController.muteDialog(dialogId, topicId, false)
            } catch (e: Exception) {
                FileLog.e(e)
            } finally {
                exitApplying()
            }
            onDone?.run()
        }
    }

    fun getNearestTransitionTimeMillis(nowMillis: Long): Long {
        var nearest = Long.MAX_VALUE
        synchronized(lock) {
            for (s in schedules.values) {
                if (!s.enabled || !s.hasValidIntervals()) continue
                val next = s.getNextTransitionTimeMillis(nowMillis)
                if (next > nowMillis && next < nearest) {
                    nearest = next
                }
            }
        }
        return if (nearest == Long.MAX_VALUE) -1L else nearest
    }

    private fun rescheduleTimersAndAlarms(rescheduleGlobalAlarm: Boolean = true) {
        val nowMillis = System.currentTimeMillis()
        val nearest = getNearestTransitionTimeMillis(nowMillis)
        AndroidUtilities.cancelRunOnUIThread(inAppCheckRunnable)
        if (nearest > nowMillis) {
            val delay = min(MAX_IN_APP_DELAY_MS, max(1000L, nearest - nowMillis + 1000L))
            AndroidUtilities.runOnUIThread(inAppCheckRunnable, delay)
        }
        if (rescheduleGlobalAlarm) {
            scheduleGlobalAlarm()
        }
    }

    companion object {
        const val ACTION_CHECK_SCHEDULE = "org.telegram.messenger.ACTION_CHECK_NOTIFICATION_MUTE_SCHEDULE"
        private const val ALARM_REQUEST_CODE = 74921
        private const val PREFS_KEY_SCHEDULES = "schedules_json_v1"
        private const val MAX_IN_APP_DELAY_MS = 24L * 60L * 60L * 1000L

        const val DAY_MON = 1 shl 0
        const val DAY_TUE = 1 shl 1
        const val DAY_WED = 1 shl 2
        const val DAY_THU = 1 shl 3
        const val DAY_FRI = 1 shl 4
        const val DAY_SAT = 1 shl 5
        const val DAY_SUN = 1 shl 6
        const val DAYS_ALL = 0x7F
        const val DAYS_WEEKDAYS = DAY_MON or DAY_TUE or DAY_WED or DAY_THU or DAY_FRI
        const val DAYS_WEEKENDS = DAY_SAT or DAY_SUN
        const val MAX_INTERVALS = 10

        @Volatile
        private var instances = arrayOfNulls<NotificationMuteScheduleController>(UserConfig.MAX_ACCOUNT_COUNT)
        private val lockObjects = Array(UserConfig.MAX_ACCOUNT_COUNT) { Any() }

        private val applyingDepth = object : ThreadLocal<Int>() {
            override fun initialValue(): Int = 0
        }

        @JvmStatic
        fun getInstance(num: Int): NotificationMuteScheduleController {
            var localInstance = instances[num]
            if (localInstance == null) {
                synchronized(lockObjects[num]) {
                    localInstance = instances[num]
                    if (localInstance == null) {
                        localInstance = NotificationMuteScheduleController(num)
                        instances[num] = localInstance
                    }
                }
            }
            return localInstance!!
        }

        @JvmStatic
        fun isApplyingSchedule(): Boolean = (applyingDepth.get() ?: 0) > 0

        private fun enterApplying() {
            applyingDepth.set((applyingDepth.get() ?: 0) + 1)
        }

        private fun exitApplying() {
            applyingDepth.set(max(0, (applyingDepth.get() ?: 0) - 1))
        }

        @Volatile
        private var lastReceiverEnabled: Boolean? = null

        @JvmStatic
        fun checkAllAccounts(fromBackground: Boolean, onDone: Runnable?) {
            checkAllAccountsWithResult(fromBackground) {
                onDone?.run()
            }
        }

        fun checkAllAccountsWithResult(fromBackground: Boolean, onDone: ((Boolean) -> Unit)?) {
            val activeAccounts = ArrayList<Int>()
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(a).isClientActivated) {
                    activeAccounts.add(a)
                }
            }
            if (activeAccounts.isEmpty()) {
                scheduleGlobalAlarm()
                onDone?.invoke(false)
                return
            }
            val anyActionApplied = AtomicBoolean(false)
            val remaining = AtomicInteger(activeAccounts.size + 1)
            val finishOne = Runnable {
                if (remaining.decrementAndGet() == 0) {
                    onDone?.invoke(anyActionApplied.get())
                }
            }
            for (account in activeAccounts) {
                getInstance(account).checkSchedulesInternal(fromBackground, rescheduleGlobalAlarm = false) { didApply ->
                    if (didApply) {
                        anyActionApplied.set(true)
                    }
                    finishOne.run()
                }
            }
            scheduleGlobalAlarm()
            finishOne.run()
        }

        private fun setReceiverEnabled(context: Context, enabled: Boolean) {
            if (lastReceiverEnabled == enabled) return
            try {
                val pm = context.packageManager ?: return
                val component = ComponentName(context, NotificationMuteScheduleReceiver::class.java)
                val currentState = pm.getComponentEnabledSetting(component)
                // Manifest declares android:enabled="false", so DEFAULT means disabled.
                val currentlyEnabled = currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                if (currentlyEnabled != enabled) {
                    val desiredState = if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    pm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP)
                }
                lastReceiverEnabled = enabled
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        @JvmStatic
        fun scheduleGlobalAlarm() {
            val context = ApplicationLoader.applicationContext ?: return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            val nowMillis = System.currentTimeMillis()
            var earliest = Long.MAX_VALUE
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (!UserConfig.getInstance(a).isClientActivated) continue
                val next = getInstance(a).getNearestTransitionTimeMillis(nowMillis)
                if (next > nowMillis && next < earliest) {
                    earliest = next
                }
            }

            val intent = Intent(context, NotificationMuteScheduleReceiver::class.java).apply {
                action = ACTION_CHECK_SCHEDULE
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            val pendingIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)

            try {
                if (earliest == Long.MAX_VALUE) {
                    alarmManager.cancel(pendingIntent)
                    setReceiverEnabled(context, false)
                    return
                }
                setReceiverEnabled(context, true)
                val triggerAtMillis = earliest + 1000L
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        }
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                    else -> {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                }
            } catch (_: SecurityException) {
                try {
                    val triggerAtMillis = earliest + 1000L
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        @JvmStatic
        fun getDayIndex(cal: Calendar): Int {
            // Calendar.SUNDAY = 1 -> 6, MONDAY = 2 -> 0, ..., SATURDAY = 7 -> 5
            return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        }

        @JvmStatic
        fun getShortDayName(dayIdx: Int): String {
            val locale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()
            var name = DayOfWeek.of(Utilities.clamp(dayIdx, 6, 0) + 1).getDisplayName(TextStyle.SHORT, locale)
            if (name.endsWith(".")) {
                name = name.dropLast(1)
            }
            if (name.isNotEmpty()) {
                name = name.substring(0, 1).uppercase(locale) + name.substring(1)
            }
            return name
        }

        @JvmStatic
        fun formatDays(daysMask: Int): String {
            val masked = daysMask and DAYS_ALL
            return when (masked) {
                0 -> LocaleController.getString(R.string.NotificationMuteScheduleNoDays)
                DAYS_ALL -> LocaleController.getString(R.string.NotificationMuteScheduleEveryDay)
                DAYS_WEEKDAYS -> LocaleController.getString(R.string.NotificationMuteScheduleWeekdays)
                DAYS_WEEKENDS -> LocaleController.getString(R.string.NotificationMuteScheduleWeekends)
                else -> (0 until 7)
                    .filter { (masked and (1 shl it)) != 0 }
                    .joinToString(", ") { getShortDayName(it) }
            }
        }

        @JvmStatic
        fun formatMinute(minuteOfDay: Int): String {
            val normalized = ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)
            val hours = normalized / 60
            val minutes = normalized % 60
            if (LocaleController.is24HourFormat) {
                return String.format(Locale.US, "%02d:%02d", hours, minutes)
            }
            // Fixed non-DST date prevents 02:xx shifting on spring-forward days in 12-hour format
            val cal = Calendar.getInstance().apply {
                clear()
                set(2020, Calendar.JANUARY, 1, hours, minutes, 0)
            }
            return LocaleController.getInstance().formatterConstDay.format(cal.time)
        }

        @JvmStatic
        fun formatTransitionTime(targetMillis: Long, nowMillis: Long): String {
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val targetCal = Calendar.getInstance().apply { timeInMillis = targetMillis }

            val minuteOfDay = targetCal.get(Calendar.HOUR_OF_DAY) * 60 + targetCal.get(Calendar.MINUTE)
            val timeStr = formatMinute(minuteOfDay)

            val sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
            if (sameDay) {
                return LocaleController.formatString(R.string.NotificationMuteScheduleTodayAt, timeStr)
            }

            val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val isTomorrow = tomorrowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
                tomorrowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
            if (isTomorrow) {
                return LocaleController.formatString(R.string.NotificationMuteScheduleTomorrowAt, timeStr)
            }

            val dayName = getShortDayName(getDayIndex(targetCal))
            return LocaleController.formatString(R.string.NotificationMuteScheduleDayAt, dayName, timeStr)
        }
    }
}
