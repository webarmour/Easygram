/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.easygram.gradle

import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.tasks.FinalizeBundleTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.telegram.tasks.TelegramStringsTask
import java.io.File

/**
 * Easygram customizations for `:TMessagesProj_App`, kept out of upstream `TMessagesProj_App/build.gradle`
 * to minimize upstream merge conflicts.
 */
class EasygramAppPlugin : Plugin<Project> {

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
        val firebaseAnalytics = libs?.findLibrary("firebase-analytics")?.orElse(null)
            ?: "com.google.firebase:firebase-analytics"
        target.dependencies.apply {
            add("implementation", target.files("../TMessagesProj/libs/video-message-recorder.aar"))
            add("implementation", target.files("../TMessagesProj/libs/sherpa-onnx-static-2.0.1-arm.aar"))
            add("implementation", kotlinStdlib)
            add("implementation", firebaseAnalytics)
        }

        val android = target.extensions.findByType(BaseAppModuleExtension::class.java)
            ?: error("Apply com.android.application before org.easygram.app")

        val localProps = EasygramLocalProperties.load(target)

        android.defaultConfig {
            minSdk = 24
        }

        val releaseKeystore = EasygramLocalProperties.get(target, "EASYGRAM_RELEASE_KEYSTORE", localProps)
        if (!releaseKeystore.isNullOrBlank()) {
            android.signingConfigs.named("release") {
                storeFile = target.file(releaseKeystore)
                storePassword = EasygramLocalProperties.get(target, "EASYGRAM_RELEASE_STORE_PASSWORD", localProps)
                keyAlias = EasygramLocalProperties.get(target, "EASYGRAM_RELEASE_KEY_ALIAS", localProps)
                keyPassword = EasygramLocalProperties.get(target, "EASYGRAM_RELEASE_KEY_PASSWORD", localProps)
            }
        }

        android.buildTypes.named("release") {
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
        }

        android.productFlavors.named("bundleAfat") {
            applicationIdSuffix = ".store"
        }

        // Variant manifests take precedence over the shared build-type manifests.
        android.sourceSets.apply {
            named("standalone") {
                manifest.srcFile("../TMessagesProj/config/release/AndroidManifest_standalone.xml")
            }
            named("afat") {
                manifest.srcFile("config/afat/AndroidManifest.xml")
            }
            maybeCreate("afatDebug").manifest.srcFile("config/analytics/AndroidManifest.xml")
            maybeCreate("afatRelease").manifest.srcFile("config/analytics/AndroidManifest.xml")
            // App Bundle = Google Play build (highest merge priority over build-type and library manifests).
            maybeCreate("bundleAfatRelease").manifest.srcFile("config/play/AndroidManifest.xml")
        }

        val appVersionName = target.property("APP_VERSION_NAME").toString()
        val appVersionCode = target.property("APP_VERSION_CODE").toString()

        android.applicationVariants.all {
            val isDebug = buildType.name == "debug"
            outputs.all {
                if (this is BaseVariantOutputImpl) {
                    outputFileName = if (isDebug) {
                        "Easygram Beta $appVersionName-$appVersionCode.apk"
                    } else {
                        "Easygram v$appVersionName-$appVersionCode.apk"
                    }
                }
            }
        }

        // App Bundle: human-readable name like the APKs (AGP has no outputFileName for bundles, so the final signed
        // bundle task's output file is renamed): "EasygramBundle v13.2.7-7334.aab"
        target.afterEvaluate {
            target.tasks.withType(FinalizeBundleTask::class.java).configureEach {
                if (name.startsWith("sign") && name.endsWith("Bundle")) {
                    val original = finalBundleFile.get().asFile
                    val bundleName = if (name.lowercase().contains("debug")) {
                        "EasygramBundle Beta $appVersionName-$appVersionCode.aab"
                    } else {
                        "EasygramBundle v$appVersionName-$appVersionCode.aab"
                    }
                    finalBundleFile.set(File(original.parentFile, bundleName))
                }
            }
        }

        // Allow isolated Easygram localization files (`values/easygram_strings.xml`, `values-*/easygram_strings.xml`)
        // to be merged into Telegram's binary localization tables without editing TelegramBuildAppPlugin.
        val telegramModule = target.project(":TMessagesProj")
        target.tasks.withType(TelegramStringsTask::class.java).configureEach {
            stringsXml.from(
                telegramModule.fileTree("src/main/res/values") {
                    include("easygram_strings.xml")
                },
                target.fileTree("src/main/res/values") {
                    include("easygram_strings.xml")
                }
            )
            localizationFiles.from(
                telegramModule.fileTree("src/main/res") {
                    include("values-*/easygram_strings.xml")
                },
                target.fileTree("src/main/res") {
                    include("values-*/easygram_strings.xml")
                }
            )
        }

        target.pluginManager.apply(EasygramAbisPlugin::class.java)
    }
}
