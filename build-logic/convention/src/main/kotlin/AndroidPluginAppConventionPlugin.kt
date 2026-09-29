/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Register `assemble${Variant}Plugins` task for root project,
 * and make all plugins' `assemble${Variant}` depends on it
 */
class AndroidPluginAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            /*
             * 【fainput】插件**必须签名** —— 否则 AGP 产出 `-release-unsigned.apk`，
             * 而 Android 拒绝安装未签名的 APK（连 debug 都自动签，release 不会）。
             *
             * 上游只在 `app` 模块配了 signingConfig，plugin 下各模块一个都没有 ——
             * 所以上游的 release 插件历来都是 unsigned 的（他们大概只用 debug 插件）。
             *
             * 用仓库里那份固定 keystore（和主 APK 同一份）：
             * 密码就是公开的 "android"，因为它本来就是 debug key（见 PROJECT.md §十一）。
             */
            signingConfigs {
                create("fainput") {
                    storeFile = target.rootProject.file("signing/debug.keystore")
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
            buildTypes {
                release {
                    // 不配这一行 ⇒ 产物叫 `-release-unsigned.apk` ⇒ 装不上。
                    signingConfig = signingConfigs.getByName("fainput")
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to "org.fcitx.fcitx5.android",
                        )
                    )
                }
                debug {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android.debug\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to "org.fcitx.fcitx5.android.debug",
                        )
                    )
                }
            }
        }
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.capitalized()
                target.afterEvaluate {
                    val pluginsTaskName = "assemble${variantName}Plugins"
                    val pluginsTask = target.rootProject.tasks.findByName(pluginsTaskName)
                        ?: target.rootProject.tasks.register(pluginsTaskName).get()
                    pluginsTask?.dependsOn(target.tasks.getByName("assemble${variantName}"))
                }
            }
        }
    }

}
