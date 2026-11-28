/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.easygram.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * Automatic versionCode: EASYGRAM_VERSION_BASE + first-parent commits since EASYGRAM_VERSION_ANCHOR
 * (easygram_version.properties). Applied to the root project; overrides APP_VERSION_CODE for all
 * modules so nothing has to be bumped by hand.
 */
class EasygramVersionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val versionProps = Properties()
        target.rootProject.file("easygram_version.properties").inputStream().use {
            versionProps.load(it)
        }
        val versionBase = versionProps.getProperty("EASYGRAM_VERSION_BASE").trim().toInt()
        val versionAnchor = versionProps.getProperty("EASYGRAM_VERSION_ANCHOR").trim()

        val commits = countCommitsSinceAnchor(target, target.rootProject.rootDir, versionAnchor)
        val versionCode = (versionBase + commits).toString()

        target.allprojects {
            extensions.extraProperties.set("APP_VERSION_CODE", versionCode)
        }

        target.tasks.register("easygramVersionCode") {
            description =
                "Prints the automatic easygram versionCode base (the APK/AAB versionCode is this * 10 + ABI code)."
            doLast {
                println(versionCode)
            }
        }
    }

    private fun countCommitsSinceAnchor(project: Project, rootDir: File, anchor: String): Int {
        return try {
            val process = ProcessBuilder("git", "rev-list", "--first-parent", "--count", "$anchor..HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0 && out.matches(Regex("\\d+"))) {
                out.toInt()
            } else {
                project.logger.warn(
                    "easygram: can't count commits since $anchor (shallow clone? not a git checkout?), versionCode = base"
                )
                0
            }
        } catch (e: Exception) {
            project.logger.warn("easygram: git is not available (${e.message}), versionCode = base")
            0
        }
    }
}
