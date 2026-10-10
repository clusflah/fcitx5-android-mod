# keep plugin classes (services/activities are referenced from the manifest, models are serialized)
-keep class org.fcitx.fcitx5.android.plugin.clipboard_sync.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
