/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

/**
 * Built-in client-side capabilities enabled by default across all build variants
 * (standalone APK and Google Play AAB) without exposing any toggle in the UI.
 *
 * Server-enforced Premium features (custom emojis, premium stickers, reactions, ads, etc.)
 * continue to rely on the real [UserConfig.isPremium] status.
 */
object EasygramLocalFeatures {

    /** Maximum number of simultaneous accounts allowed in the app (matches native tgnet MAX_ACCOUNT_COUNT = 5). */
    const val MAX_ACCOUNT_COUNT: Int = 5

    /** View stories in full HD quality by default and allow saving stories to gallery. */
    const val STORY_HD_AND_SAVE: Boolean = true

    /** Remove artificial client-side download/upload connection and byte-budget throttling. */
    const val UNLIMITED_DOWNLOAD_SPEED: Boolean = true

    /** Keep extended folder and pinned chat limits unlocked on the client. */
    const val EXTENDED_FOLDERS_AND_PINS: Boolean = true
}
