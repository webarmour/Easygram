/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

import android.app.Activity
import android.view.ViewGroup
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EasygramUpdater
import org.telegram.ui.Components.QRCodeBottomSheet
import org.telegram.ui.IUpdateLayout

object EasygramChannel {
    private const val DONATE_URL = "https://boosty.to/webarmour/donate"

    @JvmStatic
    fun takeUpdateLayout(activity: Activity?, sideMenuContainer: ViewGroup?): IUpdateLayout? {
        return EasygramUpdater.attach(activity, sideMenuContainer)
    }

    @JvmStatic
    fun checkUpdate(activity: Activity?, manual: Boolean) {
        EasygramUpdater.check(activity, manual)
    }

    @JvmStatic
    fun openSupport(fragment: BaseFragment?, resourceProvider: Theme.ResourcesProvider?) {
        val activity = fragment?.parentActivity ?: return
        val sheet = QRCodeBottomSheet(
            activity,
            LocaleController.getString(R.string.EasygramSupportAuthor),
            DONATE_URL,
            LocaleController.getString(R.string.EasygramSupportAuthorDescription) + "\n\n" + DONATE_URL,
            true,
            resourceProvider
        )
        sheet.setPrimaryAction(LocaleController.getString(R.string.EasygramSupportOnBoosty)) {
            val currentActivity = fragment.parentActivity
            if (currentActivity != null) {
                Browser.openUrlInSystemBrowser(currentActivity, DONATE_URL)
            }
        }
        fragment.showDialog(sheet)
    }
}
