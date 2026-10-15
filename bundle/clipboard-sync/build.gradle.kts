/*
 * All-in-one build: the clipboard-sync plugin (adapted from GPL-3.0 qh7574/Fcitx5-SyncClipboard) as
 * a library inside the main app, instead of a separate APK. Sources and resources stay in
 * plugin/clipboard-sync; only the manifest differs (settings open from the app's plugin list).
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard_sync"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        consumerProguardFiles("../../plugin/clipboard-sync/proguard-rules.pro")
    }

    buildTypes {
        release {
            buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android.mod.all\"")
        }
        debug {
            buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android.debug\"")
        }
    }

    lint {
        // the quick settings tile (API 24) is declared with tools:targetApi; the app's minSdk is 23
        disable += "NewApi"
    }
}

dependencies {
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
}

// the android {} accessor exposes the legacy source set type, which AGP 9 no longer implements
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main").apply {
        // built-in Kotlin only compiles extra directories added as Kotlin sources
        kotlin.srcDir("../../plugin/clipboard-sync/src/main/java")
        res.srcDir("../../plugin/clipboard-sync/src/main/res")
        manifest.srcFile("AndroidManifest.xml")
    }
}
