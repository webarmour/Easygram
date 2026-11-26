/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

class NotificationMuteScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NotificationMuteScheduleController.ACTION_CHECK_SCHEDULE &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        val pendingResult = goAsync() ?: return
        val finished = AtomicBoolean(false)
        lateinit var finishOnce: Runnable
        finishOnce = Runnable {
            if (finished.compareAndSet(false, true)) {
                AndroidUtilities.cancelRunOnUIThread(finishOnce)
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
        }

        AndroidUtilities.runOnUIThread {
            try {
                ApplicationLoader.postInitApplication()
                // Hard ceiling so goAsync() never exceeds BroadcastReceiver ANR window
                AndroidUtilities.runOnUIThread(finishOnce, 5000L)
                NotificationMuteScheduleController.checkAllAccountsWithResult(true) { didApplyActions ->
                    if (didApplyActions) {
                        AndroidUtilities.runOnUIThread(finishOnce, 1500L)
                    } else {
                        finishOnce.run()
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
                finishOnce.run()
            }
        }
    }
}
