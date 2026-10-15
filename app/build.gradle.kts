import java.net.URI
import javax.inject.Inject
import java.security.MessageDigest

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            // all-in-one: own package and name, so it installs next to the normal build
            applicationIdSuffix = ".mod.all"
            versionNameSuffix = "-all"
            resValue("string", "app_name", "@string/app_name_all")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

// ---- mod: sherpa-onnx for offline voice input (SenseVoice), see OfflineVoice.kt ----
// The AAR (~48 MB, all ABIs) is downloaded once at configuration time instead of being committed,
// and verified against its SHA-256. Uses Gradle's proxy settings (systemProp.https.proxyHost …).
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAar = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
fun sha256Of(f: File): String = MessageDigest.getInstance("SHA-256")
    .digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }
if (!sherpaOnnxAar.exists() || sha256Of(sherpaOnnxAar) != sherpaOnnxSha256) {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("Downloading $url")
    sherpaOnnxAar.parentFile.mkdirs()
    val tmp = File(sherpaOnnxAar.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
    check(sha256Of(tmp) == sherpaOnnxSha256) { "sherpa-onnx AAR checksum mismatch" }
    tmp.renameTo(sherpaOnnxAar)
}
dependencies {
    implementation(files(sherpaOnnxAar))
}
android {
    packaging {
        jniLibs {
            // only libsherpa-onnx-jni.so + libonnxruntime.so are used from Kotlin
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

// ---- all-in-one: every plugin built into the app (branch all-in-one, see BundledPlugins.kt) ----
// Native plugins (input method engines) are built as usual; their libraries and data files are
// taken from the plugin APKs: lib/<abi>/*.so go into the app's native libraries, assets/** into
// assets/bundled/<plugin>/ (with the plugin's descriptor.json), from where DataManager installs them.
// The Kotlin plugins are libraries (:bundle:*). Release builds only; debug builds stay plain.
val bundledNativePlugins = listOf("anthy", "chewing", "hangul", "jyutping", "rime", "sayura", "thai", "unikey")

abstract class BundlePluginsTask : DefaultTask() {
    /** plugin name -> its build/outputs/apk/release directory */
    @get:Input
    abstract val apkDirs: MapProperty<String, File>

    @get:InputFiles
    abstract val apkFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val rootAssets: ConfigurableFileCollection

    /** a Rime configuration laid over the Rime plugin's shared data (rime-frost); may be empty */
    @get:InputFiles
    abstract val rimeData: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val libsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun run() {
        fs.delete { delete(libsDir, assetsDir) }
        apkDirs.get().forEach { (name, dir) ->
            val apk = dir.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() }
                ?: throw GradleException("No APK of plugin $name in $dir")
            fs.copy {
                from(archives.zipTree(apk)) { include("lib/**/*.so") }
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                into(libsDir)
            }
            fs.copy {
                from(archives.zipTree(apk)) { include("assets/**") }
                eachFile {
                    relativePath = RelativePath(true, "bundled", name, *relativePath.segments.drop(1).toTypedArray())
                }
                includeEmptyDirs = false
                into(assetsDir)
            }
        }
        if (!rimeData.isEmpty) addRimeData(assetsDir.get().asFile.resolve("bundled/rime"))
        fs.copy {
            from(rootAssets)
            into(assetsDir)
        }
    }

    /** Copies [rimeData] into the Rime plugin's rime-data and rewrites its data descriptor. */
    private fun addRimeData(rime: File) {
        val descriptorFile = rime.resolve("descriptor.json")
        @Suppress("UNCHECKED_CAST")
        val old = groovy.json.JsonSlurper().parse(descriptorFile) as Map<String, Any?>
        val added = rimeData.files.flatMap { it.list()?.toList().orEmpty() }.toSet()
        // drop symlinks the new data replaces with real files (rime-data/opencc -> usr/share/opencc)
        @Suppress("UNCHECKED_CAST")
        val symlinks = (old["symlinks"] as? Map<String, String>).orEmpty().filterKeys { key ->
            !key.startsWith("usr/share/rime-data/") ||
                key.removePrefix("usr/share/rime-data/").substringBefore('/') !in added
        }
        fs.copy {
            from(rimeData)
            into(rime.resolve("usr/share/rime-data"))
        }
        // same format as DataDescriptorPlugin (:build-logic): path -> sha256, "" for directories
        val files = sortedMapOf<String, String>()
        rime.walkTopDown().filter { it != rime && it != descriptorFile }.forEach { f ->
            files[f.relativeTo(rime).invariantSeparatorsPath] = if (f.isDirectory) "" else sha256(f.readBytes())
        }
        val sha = sha256((files + symlinks).entries.joinToString { it.key + it.value }.toByteArray())
        descriptorFile.writeText(
            groovy.json.JsonOutput.toJson(mapOf("sha256" to sha, "files" to files, "symlinks" to symlinks))
        )
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

// 白霜拼音 (gaboolic/rime-frost, GPL-3.0) as the built-in Rime configuration: downloaded at a fixed
// commit and checked against a hash of its files (archives from GitHub aren't byte-stable).
// Left out: others/ (images, word lists for maintenance), the unused Tencent dictionary, README.
// To update: change the commit, then the hash (sha256 of "path sha256\n" lines, sorted by path).
val rimeFrostCommit = "b356b525200627201730b0f13f52a6b7a1500193"
val rimeFrostContentSha256 = "d13e7e9e3e91a69158cc74b67b030504b83f2d07e129944def3639e1a0dbb5ce"

abstract class FetchRimeFrostTask : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Input
    abstract val contentSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun run() {
        val archive = temporaryDir.resolve("rime-frost.tar.gz")
        val url = "https://github.com/gaboolic/rime-frost/archive/${commit.get()}.tar.gz"
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { i -> archive.outputStream().use { o -> i.copyTo(o) } }
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        fs.copy {
            from(archives.tarTree(archives.gzip(archive)))
            exclude("*/others/**", "*/README.md", "*/cn_dicts/tencent.dict.yaml", "*/.*", "*/.*/**")
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
            includeEmptyDirs = false
            into(out)
        }
        archive.delete()
        val md = MessageDigest.getInstance("SHA-256")
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val lines = out.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(out).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .joinToString("") { (path, f) -> "$path ${hex(MessageDigest.getInstance("SHA-256").digest(f.readBytes()))}\n" }
        val actual = hex(md.digest(lines.toByteArray()))
        if (actual != contentSha256.get()) throw GradleException("rime-frost content hash mismatch: $actual")
    }
}

val fetchRimeFrost = tasks.register<FetchRimeFrostTask>("fetchRimeFrost") {
    commit.set(rimeFrostCommit)
    contentSha256.set(rimeFrostContentSha256)
    outputDir.set(layout.buildDirectory.dir("rime-frost"))
}

val bundlePlugins = tasks.register<BundlePluginsTask>("bundlePlugins") {
    bundledNativePlugins.forEach { p ->
        dependsOn(":plugin:$p:assembleRelease")
        val dir = rootDir.resolve("plugin/$p/build/outputs/apk/release")
        apkDirs.put(p, dir)
        apkFiles.from(fileTree(dir) { include("*.apk") })
    }
    // ClearURLs rules read by the clipboard filter from the app's assets
    rootAssets.from(rootDir.resolve("plugin/clipboard-filter/ClearURLsRules/data.min.json"))
    rimeData.from(fetchRimeFrost.flatMap { it.outputDir })
    libsDir.set(layout.buildDirectory.dir("generated/bundled/jniLibs"))
    assetsDir.set(layout.buildDirectory.dir("generated/bundled/assets"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(bundlePlugins, BundlePluginsTask::libsDir)
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePlugins, BundlePluginsTask::assetsDir)
    }
}

dependencies {
    implementation(project(":bundle:clipboard-filter"))
    implementation(project(":bundle:clipboard-sync"))
}
