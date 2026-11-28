/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.easygram.gradle

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.telegram.tasks.localization.GenerateLocalizationUtilsJavaTask

/**
 * Easygram customizations for `:TMessagesProj`, kept out of upstream `TMessagesProj/build.gradle`
 * to minimize upstream merge conflicts.
 */
class EasygramLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("kotlin-android")

        target.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
            }
        }

        val libs = target.extensions.findByType(VersionCatalogsExtension::class.java)?.named("libs")
        @Suppress("NewApi")
        val kotlinStdlib = libs?.findLibrary("kotlin-stdlib")?.orElse(null)
            ?: "org.jetbrains.kotlin:kotlin-stdlib:2.1.0"
        @Suppress("NewApi")
        val photoPicker = libs?.findLibrary("androidx-photopicker")?.orElse(null)
            ?: "androidx.photopicker:photopicker:1.0.0-alpha02"
        target.dependencies.apply {
            add("compileOnly", target.files("libs/video-message-recorder.aar"))
            // easygram: on-device voice transcription (sherpa-onnx, Apache-2.0; arm ABIs only, see NOTICE)
            add("compileOnly", target.files("libs/sherpa-onnx-static-2.0.1-arm.aar"))
            add("implementation", kotlinStdlib)
            add("implementation", photoPicker)
        }

        val android = target.extensions.findByType(LibraryExtension::class.java)
            ?: error("Apply com.android.library before org.easygram.library")

        val localProps = EasygramLocalProperties.load(target)
        val telegramApiId = EasygramLocalProperties.get(target, "TELEGRAM_API_ID", localProps).orEmpty().ifEmpty { "0" }
        val telegramApiHash = EasygramLocalProperties.get(target, "TELEGRAM_API_HASH", localProps).orEmpty()

        android.defaultConfig {
            minSdk = 24
            buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
            buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        }

        android.buildTypes.named("debug") {
            val testApiRequested =
                EasygramLocalProperties.get(target, "EASYGRAM_USE_TELEGRAM_TEST_API", localProps)
                    .equals("true", ignoreCase = true)
            if (testApiRequested || telegramApiId == "0" || telegramApiHash.isEmpty()) {
                buildConfigField("int", "TELEGRAM_API_ID", "4")
                buildConfigField("String", "TELEGRAM_API_HASH", "\"014b35b6184100b085b0d0572f9b5103\"")
            }
        }

        // Allow isolated Easygram localization files (`values-*/easygram_strings.xml`) to be indexed
        // alongside upstream `strings.xml` without editing TelegramBuildPlugin.
        target.tasks.withType(GenerateLocalizationUtilsJavaTask::class.java).configureEach {
            localizationFiles.from(
                target.fileTree("src/main/res") {
                    include("values-*/easygram_strings.xml")
                }
            )
        }

        target.pluginManager.apply(EasygramAbisPlugin::class.java)
    }
}
