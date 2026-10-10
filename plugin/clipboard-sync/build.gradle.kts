/*
 * Clipboard sync plugin (SyncClipboard / WebDAV), adapted from
 * https://github.com/qh7574/Fcitx5-SyncClipboard (GPL-3.0) to this repository's plugin conventions,
 * so it is signed with the same key and targets this fork's main app package.
 */
plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard_sync"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.clipboard_sync"
        // quick settings tile (TileService) requires API 24
        minSdk = 24
    }

    buildTypes {
        release {
            proguardFile("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
