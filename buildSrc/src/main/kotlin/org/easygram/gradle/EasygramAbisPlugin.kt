/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.easygram.gradle

import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties

/**
 * Configures which CPU architectures (ABIs) are built and packaged in debug and release builds:
 *   debug   - arm64-v8a by default (fast native build, smaller APK)
 *   release - armeabi-v7a, arm64-v8a by default
 *
 * Override in local.properties or with -P, comma-separated:
 *   EASYGRAM_DEBUG_ABIS=arm64-v8a,x86_64
 *   EASYGRAM_RELEASE_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64
 */
class EasygramAbisPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val android = target.extensions.findByType(BaseExtension::class.java)
            ?: error("Apply an Android plugin before org.easygram.abis")

        val localProps = EasygramLocalProperties.load(target)
        val debugAbis = resolveAbiList(target, localProps, "EASYGRAM_DEBUG_ABIS", "arm64-v8a")
        val releaseAbis = resolveAbiList(target, localProps, "EASYGRAM_RELEASE_ABIS", "armeabi-v7a,arm64-v8a")

        // Clear any flavor-level ABI filters so they don't merge with buildType filters.
        android.productFlavors.all {
            ndk.abiFilters.clear()
        }

        android.buildTypes.all {
            ndk.abiFilters.clear()
            ndk.abiFilters.addAll(if (name == "debug") debugAbis else releaseAbis)
        }
    }

    private fun resolveAbiList(
        project: Project,
        localProps: Properties,
        name: String,
        defaultValue: String
    ): List<String> {
        val raw = EasygramLocalProperties.get(project, name, localProps) ?: defaultValue
        val list = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return list.ifEmpty { defaultValue.split(',') }
    }
}
