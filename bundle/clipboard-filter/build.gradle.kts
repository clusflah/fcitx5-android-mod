/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * All-in-one build: the clipboard-filter plugin's code as a library inside the main app, instead of
 * a separate APK. Sources stay in plugin/clipboard-filter; only the manifest differs (no launcher /
 * plugin intent; the app binds the service by class name, see BundledPlugins).
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard_filter"

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android.mod.all\"")
        }
        debug {
            buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android.debug\"")
        }
    }
}

dependencies {
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.serialization.json)
}

// the android {} accessor exposes the legacy source set type, which AGP 9 no longer implements
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main").apply {
        // built-in Kotlin only compiles extra directories added as Kotlin sources
        kotlin.srcDir("../../plugin/clipboard-filter/src/main/java")
        manifest.srcFile("AndroidManifest.xml")
    }
}
