/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;

/** Preferences of on-device voice transcription (Settings → Voice transcription). */
public final class EasygramAsrSettings {

    public static final int LANG_AUTO = 0, LANG_RU = 1, LANG_EN = 2;
    /** Stored values (kept for old settings): 0 balanced, 1 fast, 2 gentle. */
    public static final int PERF_BALANCED = 0, PERF_FAST = 1, PERF_GENTLE = 2;

    private static final String KEY_ENABLED = "easygram_asr_enabled";
    private static final String KEY_MODEL_PREFIX = "easygram_asr_model_";
    private static final String KEY_LANG_MODE = "easygram_asr_lang_mode";
    private static final String KEY_PERF = "easygram_asr_perf";
    private static final String KEY_WIFI_ONLY = "easygram_asr_wifi_only";
    private static final String KEY_REVEAL_HAPTIC = "easygram_asr_reveal_haptic";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    /** Local transcription is used whenever a downloaded model fits the message (also for Premium users). */
    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** The model chosen for a language; defaults to the lite one on weak devices and the optimal one otherwise. */
    public static String getSelectedModelId(String lang) {
        String id = prefs().getString(KEY_MODEL_PREFIX + lang, null);
        if (EasygramAsrModels.byId(id) != null) {
            return id;
        }
        int tier = isWeakDevice() ? EasygramAsrModels.TIER_LITE : EasygramAsrModels.TIER_OPTIMAL;
        for (EasygramAsrModels.Model m : EasygramAsrModels.forLang(lang)) {
            if (m.tier == tier) {
                return m.id;
            }
        }
        return EasygramAsrModels.forLang(lang).get(0).id;
    }

    public static void setSelectedModelId(String lang, String id) {
        prefs().edit().putString(KEY_MODEL_PREFIX + lang, id).apply();
    }

    public static int getLangMode() {
        return prefs().getInt(KEY_LANG_MODE, LANG_AUTO);
    }

    public static void setLangMode(int mode) {
        prefs().edit().putInt(KEY_LANG_MODE, mode).apply();
    }

    public static int getPerformance() {
        return prefs().getInt(KEY_PERF, isWeakDevice() ? PERF_BALANCED : PERF_FAST);
    }

    public static void setPerformance(int perf) {
        prefs().edit().putInt(KEY_PERF, perf).apply();
    }

    public static int getThreads() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int perf = getPerformance();
        return Math.min(cores, perf == PERF_FAST ? 4 : perf == PERF_GENTLE ? 1 : 2);
    }

    /**
     * Gentle mode: one thread, shorter segments and a pause after each one (half of its decode time), so the CPU
     * is never kept busy for long: the text takes longer, its quality is the same.
     */
    public static boolean isGentle() {
        return getPerformance() == PERF_GENTLE;
    }

    public static boolean isWifiOnly() {
        return prefs().getBoolean(KEY_WIFI_ONLY, true);
    }

    public static void setWifiOnly(boolean wifiOnly) {
        prefs().edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply();
    }

    /** Light haptic ticks while a transcription appears letter by letter, a stronger one when it is complete. */
    public static boolean isRevealHaptic() {
        return prefs().getBoolean(KEY_REVEAL_HAPTIC, true);
    }

    public static void setRevealHaptic(boolean enabled) {
        prefs().edit().putBoolean(KEY_REVEAL_HAPTIC, enabled).apply();
    }

    private static Boolean weakDevice;

    /** 32-bit only or under 4 GB of RAM: optimal models may be slow there. */
    public static boolean isWeakDevice() {
        Boolean cached = weakDevice;
        if (cached == null) {
            boolean weak = Build.SUPPORTED_64_BIT_ABIS.length == 0;
            try {
                ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                weak |= info.totalMem < 3_800L * 1024 * 1024;
            } catch (Exception ignore) {
            }
            weakDevice = cached = weak;
        }
        return cached;
    }

    private EasygramAsrSettings() {
    }
}
