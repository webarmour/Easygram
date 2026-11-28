/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.easygram.gradle

import org.gradle.api.Project
import java.util.Properties

internal object EasygramLocalProperties {

    fun load(project: Project): Properties {
        val props = Properties()
        val file = project.rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }

    fun get(project: Project, name: String, localProps: Properties = load(project)): String? {
        val fromGradle = project.findProperty(name)?.toString()
        if (!fromGradle.isNullOrBlank()) {
            return fromGradle.trim()
        }
        return localProps.getProperty(name)?.trim()
    }
}
