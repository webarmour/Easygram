/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicBoolean;

public class NotificationMuteScheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!NotificationMuteScheduleController.ACTION_CHECK_SCHEDULE.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true)) {
                try {
                    pendingResult.finish();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        };

        AndroidUtilities.runOnUIThread(() -> {
            try {
                ApplicationLoader.postInitApplication();
                // Allow a short window for server updateNotifySettings requests to flush cleanly
                AndroidUtilities.runOnUIThread(finishOnce, 5000);
                NotificationMuteScheduleController.checkAllAccounts(true, () ->
                        AndroidUtilities.runOnUIThread(finishOnce, 1500));
            } catch (Exception e) {
                FileLog.e(e);
                finishOnce.run();
            }
        });
    }
}
