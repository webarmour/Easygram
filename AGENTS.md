# AGENTS.md — Guidelines for AI Agents

This file defines mandatory rules and engineering practices for AI agents working in this repository (`easygram`, a fork of Telegram for Android).

---

## 1. Language & Code Conventions

* **All new source files MUST be written in Kotlin (`.kt`).**
  * Do not create new `.java` files when implementing new features, controllers, activities/fragments, receivers, views, or helpers.
  * Kotlin is enabled in `:TMessagesProj` (`kotlin-android`, `jvmTarget = "1.8"`, `kotlin-stdlib`).
  * Existing upstream Java files may be modified in place with minimal, surgical changes.
* **Java Interoperability:**
  * Because most of the upstream Telegram codebase is Java, annotate companion methods/constants called from Java with `@JvmStatic`, `@JvmField`, or `const val`, and use `@JvmOverloads` where helpful.
  * When subclassing `BaseFragment` in Kotlin, avoid naming a constructor property `resourcesProvider` if overriding `getResourceProvider()` (use e.g. `themeResourcesProvider`) to prevent JVM getter clashes.
  * Treat Java/TLRPC fields as potentially nullable (`?.`, `?:`, `.isNullOrEmpty()`) — upstream `TLRPC` classes lack nullability annotations.
* **UItem / UniversalAdapter Factories in Kotlin:**
  * Register `UItem.UItemFactory` inside `companion object { init { setup(Factory()) } }`.
  * Be careful with `contentsEquals`: if an item carries a closure referencing mutable state or resolves async data (like peers loaded from SQLite), ensure `contentsEquals` detects identity/version changes so `UniversalAdapter` rebinds the view.
* **License Header:**
  * New files should include the project GPLv2+ header:
    ```kotlin
    /*
     * This file is part of easygram, a fork of Telegram for Android.
     * It is licensed under GNU GPL v. 2 or later.
     * You should have received a copy of the license in this archive (see LICENSE).
     *
     * Copyright webarmour, 2026.
     */
    ```
* **Localization:**
  * Always add user-visible strings to **both** `TMessagesProj/src/main/res/values/strings.xml` (English) and `TMessagesProj/src/main/res/values-ru/strings.xml` (Russian).

---

## 2. UI Components & Material 3 Expressive (M3E)

* **Always use Telegram's native UI components** instead of stock Android (`ProgressBar`, `Button`, `Switch`, `AlertDialog` from `androidx`/`android.app`) or custom one-off widgets:
  * **Screens & navigation:** `BaseFragment`, `BaseFragmentSimpleGlass`, `ActionBar`, `ActionBarMenuItem`, `ActionBarPopupWindow`, `ItemOptions`.
  * **Lists & settings:** `UniversalRecyclerView` + `UniversalAdapter` + `UItem` (`HeaderCell`, `TextCell`, `TextCheckCell`, `TextSettingsCell`, `TextInfoPrivacyCell`, `ShadowSectionCell`, `UserCell`, etc.).
  * **Dialogs, sheets, feedback & buttons:** `org.telegram.ui.ActionBar.AlertDialog`, `BottomSheet`, `BulletinFactory`, `ButtonWithCounterView`.
  * **Progress & loading indicators:** `RadialProgressView`, `RadialProgress`, `RadialProgress2`, `CircularProgressDrawable`, `ContextProgressView`, `InfiniteProgress`, `MediaActionDrawable`, `LoadingDrawable`.
  * **Theming:** Always resolve colors via `Theme.getColor(..., resourcesProvider)` and pass `Theme.ResourcesProvider` through child views/dialogs so light, dark, and custom chat themes stay consistent.
* **Material 3 Expressive (M3E) mode:**
  * Check whether M3E mode is active via `OtherSettingsActivity.isMaterialExpressiveEnabled()` / `M3ExpressiveLoader.enabled()` (and `OtherSettingsActivity.isM3eVoiceEnabled()` for voice waveforms).
  * Prefer Telegram's built-in progress components (`RadialProgressView`, `RadialProgress`, `RadialProgress2`, `CircularProgressDrawable`, `ContextProgressView`, `InfiniteProgress`, `MediaActionDrawable`), which already switch to M3E visuals automatically when M3E is enabled (call `RadialProgress2.setExpressive(true, ...)` where applicable).
  * When implementing custom loading buttons, progress indicators, or media controls, always support M3E mode using easygram's dedicated M3E components:
    * **`M3ExpressiveLoader.Morph`** — shape-morphing indeterminate loader for spinners and loading buttons (see `TranscribeButton`, `RadialProgressView`, `CircularProgressDrawable`, `ContextProgressView`).
    * **`M3ExpressiveLoader.drawWavyArc` / `drawWavyProgress` / `drawWavyLinear`** — wavy determinate circular and linear progress indicators (see `RadialProgress`, `MediaActionDrawable`, `EasygramAsrModelCell`).
    * **`M3ExpressiveLoader.buildGrowingShape` / `drawGrowingShape` / `drawStop`** — M3E growing download/loading button with stop square (see `RadialProgress2`, `EasygramAsrModelCell`).
    * **`M3ExpressiveLoader.bounce` / `springIn`** — expressive spring/bounce motion when loading finishes.
    * **`M3ExpressiveTrack`** — wavy audio/video progress tracks and bar thumbs (see `SeekBar`, `SeekBarView`, `SeekBarWaveform`, `AudioPlayerAlert`, `FragmentContextView`).
    * **`M3PlayPauseDrawable`** — morphing M3E play/pause icon.

---

## 3. Think Through Corner Cases (Mandatory Audit Checklist)

Before considering any feature or fix done, explicitly review your code for edge cases across these categories:

* **Dialog & Chat Types:**
  * Private chats, basic groups, supergroups, **migrated supergroups** (`mergeDialogId != 0` — message IDs can collide across `dialogId` and `mergeDialogId`), channels, **forum topics** (`isTopic`, `topicId`), **Saved Messages** (`MODE_SAVED`), **secret/encrypted chats** (`DialogObject.isEncryptedDialog(dialogId)` — note that encrypted `dialogId` is negative, so never assume `dialogId < 0` means a `Chat`!).
* **Dates, Calendars, Timezones & DST:**
  * Never compute day counts with integer division `(end - start) / 86_400_000L` (breaks across 23-hour spring DST days — use `roundToLong()` or calendar arithmetic).
  * Always `clear()` a `Calendar` before setting `(year, month, day)` to avoid month rollover bugs on the 29th–31st.
  * Guard against `Long.MAX_VALUE` arithmetic overflow, midnight rollover when screens stay open, `LocaleController.getInstance().currentLocale == null` during cold background starts, and 12-hour vs 24-hour formats.
* **Threading, Cancellation & Resource Lifecycle:**
  * Never block the UI thread with network, SQLite, or file I/O.
  * Unregister `NotificationCenter` observers and release latches/cursors/streams in `finally` blocks.
  * Support cancellation cleanly: delete partial output files (and empty `MediaStore` rows on failure), and do **not** call `FileLoader.cancelLoadFile(String)` (which passes `deleteFile = true` and can delete cached Telegram files) — use `cancelLoadFile(doc, false)` / `cancelLoadFile(photoSize, false)`.
  * Guard `parentActivity == null`, `isShowing`, and double-taps on async actions.
* **TLRPC & Media Edge Cases:**
  * Check for `TL_inputPeerEmpty`, `TL_messageEmpty`, `TL_documentEmpty` (`id == 0L`), `TL_photoEmpty`, undownloadable photo sizes (`TL_photoStrippedSize`, `TL_photoCachedSize`, `TL_photoPathSize`), and TTL / view-once media (`ttl_seconds > 0`, `msg.ttl > 0`).
* **UI, Insets & Android API Levels:**
  * Support edge-to-edge insets (`isSupportEdgeToEdge`, `onInsets`, `BaseFragmentSimpleGlass`), RTL layouts (`LocaleController.isRTL`), dark/light themes via `Theme.ResourcesProvider`, and multi-touch cancellation in custom `View`s.
  * Respect Android API differences: `MediaStore.Downloads` (API 29+) vs legacy external storage, `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION` for `ACTION_SEND` (API 29+), `canScheduleExactAlarms()` (API 31+), `PendingIntent.FLAG_IMMUTABLE` (API 23+).
* **Security & Output Safety:**
  * Escape HTML, validate URL schemes (`http://`, `https://`, `mailto:`, `tel:`, `tg://`), URL-encode relative file paths containing `#`, `%`, `?`, or Unicode, and ensure block-level HTML tags never misnest.

---

## 4. Verification & Fixing Workflow

1. **Self-Audit After Writing Code:**
   * Re-read your changes and all call sites to verify method signatures, nullability, threading, lifecycle cleanup, and edge cases.
   * Fix every issue you find immediately rather than leaving TODOs or known edge-case bugs behind.
2. **Build & Compile Verification:**
   * Note that IntelliJ real-time analysis can show false-positive unresolved symbols for very large files (`TLRPC.java` ~72k lines, `ChatActivity.java` ~49k lines). Always verify with a real Gradle build:
     ```bash
     ./gradlew :TMessagesProj_App:assembleAfatDebug
     ```
   * If you add tests or touch tested code, run the relevant test tasks as well.
3. **Avoid OOM on Huge Upstream Files:**
   * `ChatActivity.java` (~49k lines / 2.3 MB) can trigger IDE heap OOM during large in-memory text replacements. Prefer clean extension points (such as `ActionBarMenuItem` or helper delegates) over massive edits to `ChatActivity.java` whenever architecturally appropriate.
4. **Clean Workspace:**
   * When migrating a Java file to Kotlin, delete the obsolete `.java` file and confirm via `git status` and a full Gradle build that no duplicate classes or broken callers remain.
