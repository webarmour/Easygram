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
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.IUpdateLayout

object EasygramChannel {
    @JvmStatic
    fun takeUpdateLayout(activity: Activity?, sideMenuContainer: ViewGroup?): IUpdateLayout? = null

    @JvmStatic
    fun checkUpdate(activity: Activity?, manual: Boolean) {
        // Google Play build: updates are delivered through Google Play.
    }

    @JvmStatic
    fun openSupport(fragment: BaseFragment?, resourceProvider: Theme.ResourcesProvider?) {
        // Google Play build: external donation links are disabled.
    }
}
