/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

public class NotificationMuteScheduleController extends BaseController {

    public static final String ACTION_CHECK_SCHEDULE = "org.telegram.messenger.ACTION_CHECK_NOTIFICATION_MUTE_SCHEDULE";
    private static final int ALARM_REQUEST_CODE = 74921;
    private static final String PREFS_KEY_SCHEDULES = "schedules_json_v1";
    private static final long MAX_IN_APP_DELAY_MS = 24L * 60L * 60L * 1000L;

    public static final int DAY_MON = 1 << 0;
    public static final int DAY_TUE = 1 << 1;
    public static final int DAY_WED = 1 << 2;
    public static final int DAY_THU = 1 << 3;
    public static final int DAY_FRI = 1 << 4;
    public static final int DAY_SAT = 1 << 5;
    public static final int DAY_SUN = 1 << 6;
    public static final int DAYS_ALL = 0x7F;
    public static final int DAYS_WEEKDAYS = DAY_MON | DAY_TUE | DAY_WED | DAY_THU | DAY_FRI;
    public static final int DAYS_WEEKENDS = DAY_SAT | DAY_SUN;
    public static final int MAX_INTERVALS = 10;

    private static volatile NotificationMuteScheduleController[] Instance = new NotificationMuteScheduleController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    private static final ThreadLocal<Integer> applyingDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    public static NotificationMuteScheduleController getInstance(int num) {
        NotificationMuteScheduleController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new NotificationMuteScheduleController(num);
                }
            }
        }
        return localInstance;
    }

    public static boolean isApplyingSchedule() {
        Integer depth = applyingDepth.get();
        return depth != null && depth > 0;
    }

    public static class MuteInterval {
        public int daysMask;
        public boolean allDay;
        public int startMinute;
        public int endMinute;

        public MuteInterval() {
            this(DAYS_ALL, false, 22 * 60, 8 * 60);
        }

        public MuteInterval(int daysMask, boolean allDay, int startMinute, int endMinute) {
            this.daysMask = daysMask & DAYS_ALL;
            this.allDay = allDay;
            this.startMinute = Utilities.clamp(startMinute, 24 * 60 - 1, 0);
            this.endMinute = Utilities.clamp(endMinute, 24 * 60 - 1, 0);
        }

        public MuteInterval copy() {
            return new MuteInterval(daysMask, allDay, startMinute, endMinute);
        }

        public boolean isEffectivelyAllDay() {
            return allDay || startMinute == endMinute;
        }

        public boolean isOvernight() {
            return !allDay && startMinute > endMinute;
        }

        public boolean isActiveAt(int dayIdx, int minuteOfDay) {
            if (daysMask == 0) {
                return false;
            }
            if (isEffectivelyAllDay()) {
                return (daysMask & (1 << dayIdx)) != 0;
            }
            if (startMinute < endMinute) {
                return (daysMask & (1 << dayIdx)) != 0 && minuteOfDay >= startMinute && minuteOfDay < endMinute;
            } else {
                int prevDayIdx = (dayIdx + 6) % 7;
                boolean eveningPart = (daysMask & (1 << dayIdx)) != 0 && minuteOfDay >= startMinute;
                boolean morningPart = (daysMask & (1 << prevDayIdx)) != 0 && minuteOfDay < endMinute;
                return eveningPart || morningPart;
            }
        }

        public String formatTimeRange() {
            if (isEffectivelyAllDay()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleAllDay);
            }
            return formatMinute(startMinute) + " – " + formatMinute(endMinute);
        }

        public String formatSummary() {
            return formatDays(daysMask) + " · " + formatTimeRange();
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("days", daysMask);
                obj.put("allDay", allDay);
                obj.put("start", startMinute);
                obj.put("end", endMinute);
            } catch (Exception e) {
                FileLog.e(e);
            }
            return obj;
        }

        public static MuteInterval fromJson(JSONObject obj) {
            if (obj == null) {
                return null;
            }
            int days = obj.optInt("days", DAYS_ALL) & DAYS_ALL;
            boolean allDay = obj.optBoolean("allDay", false);
            int start = obj.optInt("start", 22 * 60);
            int end = obj.optInt("end", 8 * 60);
            return new MuteInterval(days, allDay, start, end);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MuteInterval)) return false;
            MuteInterval that = (MuteInterval) o;
            return daysMask == that.daysMask &&
                    allDay == that.allDay &&
                    startMinute == that.startMinute &&
                    endMinute == that.endMinute;
        }
    }

    public static class DialogSchedule {
        public long dialogId;
        public long topicId;
        public boolean enabled;
        public final ArrayList<MuteInterval> intervals = new ArrayList<>();
        public boolean appliedMutedBySchedule;
        public int appliedMuteUntil;
        public long skipUntilMillis;

        public DialogSchedule(long dialogId, long topicId) {
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.enabled = true;
        }

        public String getKey() {
            return NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        }

        public DialogSchedule copy() {
            DialogSchedule c = new DialogSchedule(dialogId, topicId);
            c.enabled = enabled;
            c.appliedMutedBySchedule = appliedMutedBySchedule;
            c.appliedMuteUntil = appliedMuteUntil;
            c.skipUntilMillis = skipUntilMillis;
            for (int i = 0; i < intervals.size(); i++) {
                c.intervals.add(intervals.get(i).copy());
            }
            return c;
        }

        public boolean hasValidIntervals() {
            for (int i = 0; i < intervals.size(); i++) {
                if (intervals.get(i).daysMask != 0) {
                    return true;
                }
            }
            return false;
        }

        public boolean isMutedAt(Calendar cal) {
            if (!enabled || intervals.isEmpty()) {
                return false;
            }
            int dayIdx = getDayIndex(cal);
            int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            for (int i = 0; i < intervals.size(); i++) {
                if (intervals.get(i).isActiveAt(dayIdx, minuteOfDay)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isMutedAtMillis(long millis) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(millis);
            return isMutedAt(cal);
        }

        public boolean isEffectivelyMutedNow(long nowMillis) {
            return isMutedAtMillis(nowMillis) && nowMillis >= skipUntilMillis;
        }

        /**
         * Returns the exact wall-clock timestamp (ms) when the schedule's muted state next changes
         * (true -> false or false -> true), or -1 if the schedule never changes state (e.g. disabled,
         * no days selected, or muted 24/7).
         */
        public long getNextTransitionTimeMillis(long nowMillis) {
            if (!enabled || !hasValidIntervals()) {
                return -1;
            }
            boolean currentlyMuted = isMutedAtMillis(nowMillis);

            Calendar dayStart = Calendar.getInstance();
            dayStart.setTimeInMillis(nowMillis);
            dayStart.set(Calendar.HOUR_OF_DAY, 0);
            dayStart.set(Calendar.MINUTE, 0);
            dayStart.set(Calendar.SECOND, 0);
            dayStart.set(Calendar.MILLISECOND, 0);

            TreeSet<Long> candidates = new TreeSet<>();
            for (int d = 0; d <= 8; d++) {
                Calendar dayCal = (Calendar) dayStart.clone();
                dayCal.add(Calendar.DAY_OF_YEAR, d);
                int dayIdx = getDayIndex(dayCal);
                int prevDayIdx = (dayIdx + 6) % 7;

                for (int i = 0; i < intervals.size(); i++) {
                    MuteInterval interval = intervals.get(i);
                    if (interval.daysMask == 0) {
                        continue;
                    }
                    if (interval.isEffectivelyAllDay()) {
                        if ((interval.daysMask & (1 << dayIdx)) != 0) {
                            addCandidate(candidates, dayCal, 0, nowMillis);
                            Calendar nextDay = (Calendar) dayCal.clone();
                            nextDay.add(Calendar.DAY_OF_YEAR, 1);
                            addCandidate(candidates, nextDay, 0, nowMillis);
                        }
                    } else if (interval.startMinute < interval.endMinute) {
                        if ((interval.daysMask & (1 << dayIdx)) != 0) {
                            addCandidate(candidates, dayCal, interval.startMinute, nowMillis);
                            addCandidate(candidates, dayCal, interval.endMinute, nowMillis);
                        }
                    } else {
                        if ((interval.daysMask & (1 << dayIdx)) != 0) {
                            addCandidate(candidates, dayCal, interval.startMinute, nowMillis);
                        }
                        if ((interval.daysMask & (1 << prevDayIdx)) != 0) {
                            addCandidate(candidates, dayCal, interval.endMinute, nowMillis);
                        }
                    }
                }
            }

            Calendar probe = Calendar.getInstance();
            for (Long ts : candidates) {
                probe.setTimeInMillis(ts);
                if (isMutedAt(probe) != currentlyMuted) {
                    return ts;
                }
            }
            return -1;
        }

        private static void addCandidate(TreeSet<Long> candidates, Calendar dayCal, int minuteOfDay, long nowMillis) {
            Calendar c = (Calendar) dayCal.clone();
            c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
            c.set(Calendar.MINUTE, minuteOfDay % 60);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long millis = c.getTimeInMillis();
            if (millis > nowMillis) {
                candidates.add(millis);
            }
        }

        public String formatSummary() {
            if (intervals.isEmpty()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleOff);
            }
            String first = intervals.get(0).formatSummary();
            if (intervals.size() == 1) {
                return first;
            }
            return first + " (" + LocaleController.formatString(R.string.NotificationMuteScheduleMoreIntervals, intervals.size() - 1) + ")";
        }

        public String formatStatusText(long nowMillis) {
            if (!enabled) {
                return LocaleController.getString(R.string.NotificationMuteScheduleStatusDisabled);
            }
            if (!hasValidIntervals()) {
                return LocaleController.getString(R.string.NotificationMuteScheduleNoDays);
            }
            boolean inWindow = isMutedAtMillis(nowMillis);
            long nextTransition = getNextTransitionTimeMillis(nowMillis);
            if (inWindow) {
                if (nowMillis < skipUntilMillis) {
                    long afterWindowEnd = skipUntilMillis;
                    long nextStart = getNextTransitionTimeMillis(afterWindowEnd + 1000L);
                    if (nextStart > 0) {
                        return LocaleController.formatString(R.string.NotificationMuteScheduleStatusManualUnmute, formatTransitionTime(nextStart, nowMillis));
                    }
                }
                if (nextTransition < 0) {
                    return LocaleController.getString(R.string.NotificationMuteScheduleStatusMuted247);
                }
                return LocaleController.formatString(R.string.NotificationMuteScheduleStatusMutedUntil, formatTransitionTime(nextTransition, nowMillis));
            } else {
                if (nextTransition < 0) {
                    return LocaleController.getString(R.string.NotificationMuteScheduleStatusDisabled);
                }
                return LocaleController.formatString(R.string.NotificationMuteScheduleStatusActiveUntil, formatTransitionTime(nextTransition, nowMillis));
            }
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("dialogId", dialogId);
                obj.put("topicId", topicId);
                obj.put("enabled", enabled);
                obj.put("appliedMuted", appliedMutedBySchedule);
                obj.put("appliedUntil", appliedMuteUntil);
                obj.put("skipUntil", skipUntilMillis);
                JSONArray arr = new JSONArray();
                for (int i = 0; i < intervals.size(); i++) {
                    arr.put(intervals.get(i).toJson());
                }
                obj.put("intervals", arr);
            } catch (Exception e) {
                FileLog.e(e);
            }
            return obj;
        }

        public static DialogSchedule fromJson(JSONObject obj) {
            if (obj == null) {
                return null;
            }
            long dialogId = obj.optLong("dialogId", 0);
            long topicId = obj.optLong("topicId", 0);
            if (dialogId == 0) {
                return null;
            }
            DialogSchedule schedule = new DialogSchedule(dialogId, topicId);
            schedule.enabled = obj.optBoolean("enabled", true);
            schedule.appliedMutedBySchedule = obj.optBoolean("appliedMuted", false);
            schedule.appliedMuteUntil = obj.optInt("appliedUntil", 0);
            schedule.skipUntilMillis = obj.optLong("skipUntil", 0);
            JSONArray arr = obj.optJSONArray("intervals");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    MuteInterval interval = MuteInterval.fromJson(arr.optJSONObject(i));
                    if (interval != null) {
                        schedule.intervals.add(interval);
                    }
                }
            }
            return schedule;
        }
    }

    private final Object lock = new Object();
    private final LinkedHashMap<String, DialogSchedule> schedules = new LinkedHashMap<>();
    private final SharedPreferences preferences;
    private final Runnable inAppCheckRunnable = () -> checkSchedules(false, null);

    public NotificationMuteScheduleController(int num) {
        super(num);
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(
                "notification_mute_schedules_" + currentAccount, Activity.MODE_PRIVATE);
        loadFromPrefs();
    }

    private void loadFromPrefs() {
        synchronized (lock) {
            schedules.clear();
            String json = preferences.getString(PREFS_KEY_SCHEDULES, null);
            if (TextUtils.isEmpty(json)) {
                return;
            }
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    DialogSchedule s = DialogSchedule.fromJson(arr.optJSONObject(i));
                    if (s != null) {
                        schedules.put(s.getKey(), s);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private void saveToPrefsLocked() {
        JSONArray arr = new JSONArray();
        for (DialogSchedule s : schedules.values()) {
            arr.put(s.toJson());
        }
        preferences.edit().putString(PREFS_KEY_SCHEDULES, arr.toString()).apply();
    }

    @Nullable
    public DialogSchedule getSchedule(long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        synchronized (lock) {
            DialogSchedule s = schedules.get(key);
            return s != null ? s.copy() : null;
        }
    }

    @Nullable
    public String getScheduleSummary(long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        synchronized (lock) {
            DialogSchedule s = schedules.get(key);
            if (s == null || !s.enabled || !s.hasValidIntervals()) {
                return null;
            }
            return s.formatSummary();
        }
    }

    public ArrayList<DialogSchedule> getAllSchedules() {
        synchronized (lock) {
            ArrayList<DialogSchedule> result = new ArrayList<>(schedules.size());
            for (DialogSchedule s : schedules.values()) {
                result.add(s.copy());
            }
            return result;
        }
    }

    public int getEnabledSchedulesCount() {
        synchronized (lock) {
            int count = 0;
            for (DialogSchedule s : schedules.values()) {
                if (s.enabled && s.hasValidIntervals()) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Fast check used by NotificationsController.getNotifyOverride() as defense-in-depth
     * in case an incoming push arrives during a muted interval before the server mute sync completed.
     */
    public boolean isDialogScheduledMutedNow(long dialogId, long topicId) {
        if (!getUserConfig().isClientActivated()) {
            return false;
        }
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        boolean mutedNow = false;
        boolean needsSync = false;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            DialogSchedule s = schedules.get(key);
            if (s != null && s.enabled && s.hasValidIntervals()) {
                mutedNow = s.isEffectivelyMutedNow(now);
                if (mutedNow && !s.appliedMutedBySchedule) {
                    needsSync = true;
                }
            }
        }
        if (needsSync) {
            AndroidUtilities.runOnUIThread(() -> checkSchedules(true, null));
        }
        return mutedNow;
    }

    public void saveSchedule(@NonNull DialogSchedule updated) {
        if (updated.dialogId == 0) {
            return;
        }
        boolean needUnmuteNow = false;
        String key = updated.getKey();
        synchronized (lock) {
            DialogSchedule existing = schedules.get(key);
            DialogSchedule stored = updated.copy();
            if (existing != null) {
                stored.appliedMutedBySchedule = existing.appliedMutedBySchedule;
                stored.appliedMuteUntil = existing.appliedMuteUntil;
            }
            stored.skipUntilMillis = 0;
            if (!stored.enabled && stored.appliedMutedBySchedule) {
                needUnmuteNow = true;
                stored.appliedMutedBySchedule = false;
                stored.appliedMuteUntil = 0;
            }
            schedules.put(key, stored);
            saveToPrefsLocked();
        }
        if (needUnmuteNow) {
            applyUnmuteAsync(updated.dialogId, updated.topicId, null);
        }
        checkSchedules(false, null);
        AndroidUtilities.runOnUIThread(() ->
                getNotificationCenter().postNotificationName(NotificationCenter.notificationsSettingsUpdated));
    }

    public void setScheduleEnabled(long dialogId, long topicId, boolean enabled) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        boolean needUnmuteNow = false;
        boolean changed = false;
        synchronized (lock) {
            DialogSchedule s = schedules.get(key);
            if (s == null || s.enabled == enabled) {
                return;
            }
            s.enabled = enabled;
            changed = true;
            if (enabled) {
                s.skipUntilMillis = 0;
            } else if (s.appliedMutedBySchedule) {
                s.appliedMutedBySchedule = false;
                s.appliedMuteUntil = 0;
                needUnmuteNow = true;
            }
            saveToPrefsLocked();
        }
        if (changed) {
            if (needUnmuteNow) {
                applyUnmuteAsync(dialogId, topicId, null);
            }
            checkSchedules(false, null);
            AndroidUtilities.runOnUIThread(() ->
                    getNotificationCenter().postNotificationName(NotificationCenter.notificationsSettingsUpdated));
        }
    }

    public void removeSchedule(long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        boolean needUnmuteNow = false;
        synchronized (lock) {
            DialogSchedule removed = schedules.remove(key);
            if (removed == null) {
                return;
            }
            needUnmuteNow = removed.appliedMutedBySchedule;
            saveToPrefsLocked();
        }
        if (needUnmuteNow) {
            applyUnmuteAsync(dialogId, topicId, null);
        }
        rescheduleTimersAndAlarms();
        AndroidUtilities.runOnUIThread(() ->
                getNotificationCenter().postNotificationName(NotificationCenter.notificationsSettingsUpdated));
    }

    /**
     * Called by NotificationsController when the user manually changes a dialog's mute state.
     * If the user manually unmutes during an active quiet window, we pause re-muting until the
     * end of the current quiet window so we don't fight the user's explicit action.
     */
    public void onManualMuteChange(long dialogId, long topicId, boolean muted) {
        if (isApplyingSchedule()) {
            return;
        }
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            DialogSchedule s = schedules.get(key);
            if (s == null || !s.enabled || !s.hasValidIntervals()) {
                return;
            }
            if (!muted) {
                if (s.isMutedAtMillis(now)) {
                    long windowEnd = s.getNextTransitionTimeMillis(now);
                    s.skipUntilMillis = windowEnd > now ? windowEnd : Long.MAX_VALUE;
                }
                s.appliedMutedBySchedule = false;
                s.appliedMuteUntil = 0;
                saveToPrefsLocked();
            } else {
                if (s.skipUntilMillis > 0 && s.isMutedAtMillis(now)) {
                    s.skipUntilMillis = 0;
                    saveToPrefsLocked();
                }
            }
        }
    }

    public void checkSchedules(boolean fromBackground, @Nullable Runnable onDone) {
        if (!getUserConfig().isClientActivated()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        final long nowMillis = System.currentTimeMillis();
        final ArrayList<PendingAction> actions = new ArrayList<>();
        boolean prefsDirty = false;

        synchronized (lock) {
            for (DialogSchedule s : schedules.values()) {
                if (!s.enabled || !s.hasValidIntervals()) {
                    if (s.appliedMutedBySchedule) {
                        s.appliedMutedBySchedule = false;
                        s.appliedMuteUntil = 0;
                        prefsDirty = true;
                        actions.add(new PendingAction(s.dialogId, s.topicId, false, 0));
                    }
                    continue;
                }

                if (s.skipUntilMillis > 0 && nowMillis >= s.skipUntilMillis) {
                    s.skipUntilMillis = 0;
                    prefsDirty = true;
                }

                boolean inWindow = s.isMutedAtMillis(nowMillis);
                boolean shouldMute = inWindow && nowMillis >= s.skipUntilMillis;

                if (shouldMute) {
                    long nextEndMillis = s.getNextTransitionTimeMillis(nowMillis);
                    int muteDurationSec;
                    int expectedServerUntilSec;
                    if (nextEndMillis < 0) {
                        muteDurationSec = Integer.MAX_VALUE;
                        expectedServerUntilSec = Integer.MAX_VALUE;
                    } else {
                        long remainingSec = Math.max(1L, (long) Math.ceil((nextEndMillis - nowMillis) / 1000.0));
                        muteDurationSec = (int) Math.min(Integer.MAX_VALUE - 1, remainingSec);
                        expectedServerUntilSec = getConnectionsManager().getCurrentTime() + muteDurationSec;
                    }

                    boolean alreadyMuted = getMessagesController().isDialogMuted(s.dialogId, s.topicId);
                    boolean sameWindow = s.appliedMutedBySchedule && alreadyMuted && (
                            (expectedServerUntilSec == Integer.MAX_VALUE && s.appliedMuteUntil == Integer.MAX_VALUE) ||
                            (expectedServerUntilSec != Integer.MAX_VALUE && Math.abs(s.appliedMuteUntil - expectedServerUntilSec) <= 90)
                    );

                    if (!sameWindow) {
                        s.appliedMutedBySchedule = true;
                        s.appliedMuteUntil = expectedServerUntilSec;
                        prefsDirty = true;
                        actions.add(new PendingAction(s.dialogId, s.topicId, true, muteDurationSec));
                    }
                } else {
                    if (s.appliedMutedBySchedule) {
                        s.appliedMutedBySchedule = false;
                        s.appliedMuteUntil = 0;
                        prefsDirty = true;
                        actions.add(new PendingAction(s.dialogId, s.topicId, false, 0));
                    }
                }
            }
            if (prefsDirty) {
                saveToPrefsLocked();
            }
        }

        rescheduleTimersAndAlarms();

        if (actions.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        if (fromBackground) {
            getConnectionsManager().resumeNetworkMaybe();
        }

        final AtomicInteger remaining = new AtomicInteger(actions.size());
        final Runnable actionFinished = () -> {
            if (remaining.decrementAndGet() == 0 && onDone != null) {
                onDone.run();
            }
        };

        for (int i = 0; i < actions.size(); i++) {
            PendingAction action = actions.get(i);
            if (action.mute) {
                applyMuteAsync(action.dialogId, action.topicId, action.muteDurationSeconds, actionFinished);
            } else {
                applyUnmuteAsync(action.dialogId, action.topicId, actionFinished);
            }
        }
    }

    private static class PendingAction {
        final long dialogId;
        final long topicId;
        final boolean mute;
        final int muteDurationSeconds;

        PendingAction(long dialogId, long topicId, boolean mute, int muteDurationSeconds) {
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.mute = mute;
            this.muteDurationSeconds = muteDurationSeconds;
        }
    }

    private void ensurePeerLoadedAndRun(long dialogId, Runnable uiAction) {
        boolean needChat = dialogId < 0 && getMessagesController().getChat(-dialogId) == null;
        boolean needUser = dialogId > 0 && dialogId != getUserConfig().getClientUserId() && getMessagesController().getUser(dialogId) == null;
        if (!needChat && !needUser) {
            AndroidUtilities.runOnUIThread(uiAction);
            return;
        }
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            if (needChat) {
                TLRPC.Chat chat = getMessagesStorage().getChat(-dialogId);
                if (chat != null) {
                    getMessagesController().putChat(chat, true);
                }
            } else if (needUser) {
                TLRPC.User user = getMessagesStorage().getUser(dialogId);
                if (user != null) {
                    getMessagesController().putUser(user, true);
                }
            }
            AndroidUtilities.runOnUIThread(uiAction);
        });
    }

    private void applyMuteAsync(long dialogId, long topicId, int durationSeconds, @Nullable Runnable onDone) {
        ensurePeerLoadedAndRun(dialogId, () -> {
            enterApplying();
            try {
                getNotificationsController().muteUntil(dialogId, topicId, durationSeconds);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                exitApplying();
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    private void applyUnmuteAsync(long dialogId, long topicId, @Nullable Runnable onDone) {
        ensurePeerLoadedAndRun(dialogId, () -> {
            enterApplying();
            try {
                SharedPreferences prefs = MessagesController.getNotificationsSettings(currentAccount);
                String key = NotificationsController.getSharedPrefKey(dialogId, topicId);
                prefs.edit().remove("notifyuntil_" + key).apply();
                getNotificationsController().muteDialog(dialogId, topicId, false);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                exitApplying();
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    private static void enterApplying() {
        Integer d = applyingDepth.get();
        applyingDepth.set((d == null ? 0 : d) + 1);
    }

    private static void exitApplying() {
        Integer d = applyingDepth.get();
        applyingDepth.set(Math.max(0, (d == null ? 0 : d) - 1));
    }

    public long getNearestTransitionTimeMillis(long nowMillis) {
        long nearest = Long.MAX_VALUE;
        synchronized (lock) {
            for (DialogSchedule s : schedules.values()) {
                if (!s.enabled || !s.hasValidIntervals()) {
                    continue;
                }
                long next = s.getNextTransitionTimeMillis(nowMillis);
                if (next > nowMillis && next < nearest) {
                    nearest = next;
                }
            }
        }
        return nearest == Long.MAX_VALUE ? -1 : nearest;
    }

    private void rescheduleTimersAndAlarms() {
        long nowMillis = System.currentTimeMillis();
        long nearest = getNearestTransitionTimeMillis(nowMillis);
        AndroidUtilities.cancelRunOnUIThread(inAppCheckRunnable);
        if (nearest > nowMillis) {
            long delay = Math.min(MAX_IN_APP_DELAY_MS, Math.max(1000L, nearest - nowMillis + 1000L));
            AndroidUtilities.runOnUIThread(inAppCheckRunnable, delay);
        }
        scheduleGlobalAlarm();
    }

    public static void checkAllAccounts(boolean fromBackground, @Nullable Runnable onDone) {
        ArrayList<Integer> activeAccounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                activeAccounts.add(a);
            }
        }
        if (activeAccounts.isEmpty()) {
            scheduleGlobalAlarm();
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        AtomicInteger remaining = new AtomicInteger(activeAccounts.size());
        Runnable accountDone = () -> {
            if (remaining.decrementAndGet() == 0 && onDone != null) {
                onDone.run();
            }
        };
        for (int i = 0; i < activeAccounts.size(); i++) {
            getInstance(activeAccounts.get(i)).checkSchedules(fromBackground, accountDone);
        }
    }

    public static void scheduleGlobalAlarm() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            long next = getInstance(a).getNearestTransitionTimeMillis(nowMillis);
            if (next > nowMillis && next < earliest) {
                earliest = next;
            }
        }

        Intent intent = new Intent(context, NotificationMuteScheduleReceiver.class);
        intent.setAction(ACTION_CHECK_SCHEDULE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);

        try {
            if (earliest == Long.MAX_VALUE) {
                alarmManager.cancel(pendingIntent);
                return;
            }
            long triggerAtMillis = earliest + 1000L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (SecurityException se) {
            try {
                long triggerAtMillis = earliest + 1000L;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static int getDayIndex(Calendar cal) {
        // Calendar.SUNDAY = 1 -> 6, MONDAY = 2 -> 0, ..., SATURDAY = 7 -> 5
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    public static String getShortDayName(int dayIdx) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        String name = DayOfWeek.values()[Utilities.clamp(dayIdx, 6, 0)].getDisplayName(TextStyle.SHORT, locale);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (!name.isEmpty()) {
            name = name.substring(0, 1).toUpperCase(locale) + name.substring(1);
        }
        return name;
    }

    public static String formatDays(int daysMask) {
        daysMask &= DAYS_ALL;
        if (daysMask == 0) {
            return LocaleController.getString(R.string.NotificationMuteScheduleNoDays);
        }
        if (daysMask == DAYS_ALL) {
            return LocaleController.getString(R.string.NotificationMuteScheduleEveryDay);
        }
        if (daysMask == DAYS_WEEKDAYS) {
            return LocaleController.getString(R.string.NotificationMuteScheduleWeekdays);
        }
        if (daysMask == DAYS_WEEKENDS) {
            return LocaleController.getString(R.string.NotificationMuteScheduleWeekends);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((daysMask & (1 << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(getShortDayName(i));
            }
        }
        return sb.toString();
    }

    public static String formatMinute(int minuteOfDay) {
        minuteOfDay = ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60);
        int hours = minuteOfDay / 60;
        int minutes = minuteOfDay % 60;
        if (LocaleController.is24HourFormat) {
            return String.format(Locale.US, "%02d:%02d", hours, minutes);
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hours);
        cal.set(Calendar.MINUTE, minutes);
        return LocaleController.getInstance().getFormatterConstDay().format(cal.getTime());
    }

    public static String formatTransitionTime(long targetMillis, long nowMillis) {
        Calendar nowCal = Calendar.getInstance();
        nowCal.setTimeInMillis(nowMillis);
        Calendar targetCal = Calendar.getInstance();
        targetCal.setTimeInMillis(targetMillis);

        int minuteOfDay = targetCal.get(Calendar.HOUR_OF_DAY) * 60 + targetCal.get(Calendar.MINUTE);
        String timeStr = formatMinute(minuteOfDay);

        boolean sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)
                && nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return LocaleController.formatString(R.string.NotificationMuteScheduleTodayAt, timeStr);
        }

        Calendar tomorrowCal = (Calendar) nowCal.clone();
        tomorrowCal.add(Calendar.DAY_OF_YEAR, 1);
        boolean isTomorrow = tomorrowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)
                && tomorrowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR);
        if (isTomorrow) {
            return LocaleController.formatString(R.string.NotificationMuteScheduleTomorrowAt, timeStr);
        }

        String dayName = getShortDayName(getDayIndex(targetCal));
        return LocaleController.formatString(R.string.NotificationMuteScheduleDayAt, dayName, timeStr);
    }
}
