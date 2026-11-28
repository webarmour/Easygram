/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.ProfileSearchCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import kotlin.math.abs

object EasygramContentFilter {

    const val HOLD_MS: Long = 3_000L
    const val TTL_MS: Long = 24L * 60L * 60L * 1000L

    private const val PREFS_NAME = "easygram_content_state"
    private const val CLOCK_TOLERANCE_MS = 60_000L

    private val lock = Any()
    private var cache: HashMap<String, Long>? = null

    private fun prefs(): SharedPreferences? {
        val context = ApplicationLoader.applicationContext ?: return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureCacheLocked(now: Long): HashMap<String, Long> {
        cache?.let { return it }
        val loaded = HashMap<String, Long>()
        val preferences = prefs()
        if (preferences != null) {
            var editor: SharedPreferences.Editor? = null
            for ((key, value) in preferences.all) {
                val expiresAt = value as? Long ?: continue
                if (expiresAt > now) {
                    loaded[key] = expiresAt
                } else {
                    if (editor == null) {
                        editor = preferences.edit()
                    }
                    editor.remove(key)
                }
            }
            editor?.apply()
        }
        cache = loaded
        return loaded
    }

    private fun dialogKey(account: Int, dialogId: Long): String = "d_${account}_${dialogId}"

    private fun searchKey(account: Int): String = "s_${account}"

    private fun isKeyActive(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val map = ensureCacheLocked(now)
            val expiresAt = map[key] ?: return false
            if (now >= expiresAt) {
                map.remove(key)
                prefs()?.edit()?.remove(key)?.apply()
                return false
            }
            if (expiresAt - now > TTL_MS + CLOCK_TOLERANCE_MS) {
                val clamped = now + TTL_MS
                map[key] = clamped
                prefs()?.edit()?.putLong(key, clamped)?.apply()
            }
            return true
        }
    }

    private fun markKey(key: String) {
        val now = System.currentTimeMillis()
        val expiresAt = now + TTL_MS
        synchronized(lock) {
            val map = ensureCacheLocked(now)
            map[key] = expiresAt
            prefs()?.edit()?.putLong(key, expiresAt)?.apply()
        }
    }

    @JvmStatic
    fun isDialogFiltered(account: Int, dialogId: Long): Boolean {
        return isKeyActive(dialogKey(account, dialogId))
    }

    @JvmStatic
    fun filterDialog(account: Int, dialogId: Long) {
        markKey(dialogKey(account, dialogId))
    }

    @JvmStatic
    fun isSearchFiltered(account: Int): Boolean {
        return isKeyActive(searchKey(account))
    }

    @JvmStatic
    fun filterSearch(account: Int) {
        markKey(searchKey(account))
    }

    @JvmStatic
    fun isFilterableCell(view: View?): Boolean {
        if (view is ChatMessageCell) {
            val msg = view.messageObject
            return msg != null && msg.isSponsored()
        }
        if (view is ProfileSearchCell) {
            return view.hasMetaTag()
        }
        return false
    }

    @JvmStatic
    fun clearFilteredInAdapter(account: Int, dialogId: Long, adapter: RecyclerView.Adapter<*>?) {
        filterDialog(account, dialogId)
        MessagesController.getInstance(account).clearDialogExtras(dialogId)
        val chatAdapter = adapter as? ChatActivity.ChatActivityAdapter ?: return
        val messages = chatAdapter.messages ?: return
        var removedAny = false
        for (i in messages.size - 1 downTo 0) {
            val msg = messages[i]
            if (msg != null && msg.isSponsored()) {
                messages.removeAt(i)
                removedAny = true
                if (!chatAdapter.isFiltered && !chatAdapter.isFrozen) {
                    chatAdapter.notifyItemRemoved(chatAdapter.messagesStartRow + i)
                }
            }
        }
        if (removedAny && (chatAdapter.isFiltered || chatAdapter.isFrozen)) {
            chatAdapter.notifyDataSetChanged()
        }
    }

    @JvmStatic
    fun syncVisibleDialog(account: Int, dialogId: Long) {
        val lastFragment = LaunchActivity.getSafeLastFragment()
        if (lastFragment is ChatActivity && lastFragment.currentAccount == account && lastFragment.dialogId == dialogId) {
            val listView = lastFragment.chatListView
            if (listView != null) {
                clearFilteredInAdapter(account, dialogId, listView.adapter)
            }
        }
    }

    /**
     * Tracks a continuous long-hold gesture on content containers without feedback.
     */
    class HoldGestureTracker(
        context: Context,
        private val cancelCallback: CancelCallback,
        private val onTriggered: Runnable
    ) {
        fun interface CancelCallback {
            fun cancelGesture(cancelEvent: MotionEvent)
        }

        private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var swallowing = false
        private var dispatchingCancel = false

        private val holdRunnable = Runnable {
            if (!tracking) {
                return@Runnable
            }
            tracking = false
            swallowing = true
            val now = SystemClock.uptimeMillis()
            val cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            dispatchingCancel = true
            try {
                cancelCallback.cancelGesture(cancelEvent)
            } finally {
                dispatchingCancel = false
                cancelEvent.recycle()
            }
            onTriggered.run()
        }

        fun onTouchEvent(ev: MotionEvent, canStart: Boolean): Boolean {
            if (dispatchingCancel) {
                return false
            }
            val action = ev.actionMasked
            if (swallowing) {
                if (action == MotionEvent.ACTION_DOWN) {
                    swallowing = false
                } else {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        swallowing = false
                    }
                    return true
                }
            }
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    if (canStart) {
                        downX = ev.rawX
                        downY = ev.rawY
                        tracking = true
                        AndroidUtilities.runOnUIThread(holdRunnable, HOLD_MS)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        if (ev.pointerCount > 1 ||
                            abs(ev.rawX - downX) > touchSlop ||
                            abs(ev.rawY - downY) > touchSlop
                        ) {
                            cancel()
                        }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    cancel()
                }
            }
            return false
        }

        fun cancel() {
            tracking = false
            AndroidUtilities.cancelRunOnUIThread(holdRunnable)
        }

        fun reset() {
            cancel()
            swallowing = false
        }
    }
}
