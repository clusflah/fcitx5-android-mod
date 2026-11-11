/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: in-app update from this fork's GitHub Releases, with full post-current-version changelog.
 */
package org.fcitx.fcitx5.android.data.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.fcitx.fcitx5.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 只给更新安装包用的 FileProvider（自己的类名，免得和别的库的 FileProvider 在清单里撞名） */
class UpdateFileProvider : FileProvider()

/**
 * 应用内更新：查 GitHub Releases 上最新的版本，比当前版本新就提示，并汇总显示当前版本之后的全部更新日志，
 * 下载安装包后交给系统安装。
 *  - 自动检查（[autoCheck]）：打开 App 时最多一天查一次，失败不打扰；用户点了“跳过此版本”的不再提示
 *  - 手动检查（[checkNow]）：设置里的“检查更新”，结果都会告诉用户
 * 下载完先核对安装包的包名，和自己不一样（比如下错了版本）就不装。
 * 用的是系统自带的对话框和网络库，不依赖界面框架。
 */
class AppUpdater(
    /** 例如 "clusflah/fcitx5-android-mod" */
    private val repo: String,
    /** 哪些发布算数（比如同一个仓库里还有别的版本线） */
    private val acceptTag: (String) -> Boolean = { true },
    /** 从这次发布的安装包文件名里挑出适合这台手机、这个版本的那个；没有返回 null */
    private val pickAsset: (List<String>) -> String?,
    /** 当前安装包编译时的 Git 提交哈希（当本地编译版本没有对应 tag 时用于比对提交记录） */
    private val currentGitHash: String = "",
) {
    class Release(
        val tag: String,
        val notes: String,
        val url: String,
        val fileName: String,
        val size: Long,
        val page: String
    )

    private data class CandidateRelease(
        val tag: String,
        val targetCommitish: String,
        val publishedAt: String,
        val rawBody: String,
        val url: String,
        val fileName: String,
        val size: Long,
        val page: String
    )

    private data class CommitItem(
        val sha: String,
        val subject: String,
        val date: String
    )

    private val main = Handler(Looper.getMainLooper())

    /** 装在手机上的版本号（versionName） */
    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun httpGetJson(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", repo)
            if (conn.responseCode !in 200..299) throw java.io.IOException("GitHub 返回 ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 最新的可用发布；传入 [installedVersion] 时会自动汇总该版本之后的全部更新日志 */
    fun fetchLatest(installedVersion: String? = null): Release? {
        val text = httpGetJson("https://api.github.com/repos/$repo/releases?per_page=30")
        val list = JSONArray(text)
        val allByTag = LinkedHashMap<String, JSONObject>()
        val matching = ArrayList<CandidateRelease>()

        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (tag.isNotEmpty()) {
                allByTag[tag] = r
            }
            if (!acceptTag(tag)) continue
            val assets = r.optJSONArray("assets") ?: continue
            val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
            val pick = pickAsset(names) ?: continue
            val a = assets.getJSONObject(names.indexOf(pick))
            matching += CandidateRelease(
                tag = tag,
                targetCommitish = r.optString("target_commitish"),
                publishedAt = r.optString("published_at"),
                rawBody = r.optString("body"),
                url = a.optString("browser_download_url"),
                fileName = pick,
                size = a.optLong("size"),
                page = r.optString("html_url")
            )
        }

        val latest = matching.firstOrNull() ?: return null
        if (installedVersion == null || !isNewer(latest.tag, installedVersion)) {
            return Release(latest.tag, latest.rawBody, latest.url, latest.fileName, latest.size, latest.page)
        }

        val changelog = buildChangelogSince(installedVersion, matching, allByTag)
        return Release(latest.tag, changelog, latest.url, latest.fileName, latest.size, latest.page)
    }

    private fun buildChangelogSince(
        installedVersion: String,
        matching: List<CandidateRelease>,
        allByTag: Map<String, JSONObject>
    ): String {
        val newerReleases = matching.filter { isNewer(it.tag, installedVersion) }
        val latest = newerReleases.firstOrNull() ?: matching.first()

        // 1. Try exact Git commit range between installed version and latest release
        val commits = fetchCommitsBetween(installedVersion, latest.tag, allByTag)
        if (!commits.isNullOrEmpty()) {
            return formatGroupedCommits(
                commitsOldestFirst = commits,
                newerReleasesNewestFirst = newerReleases,
                allFetchedNewer = newerReleases.isNotEmpty() && newerReleases.size == matching.size,
                allByTag = allByTag
            )
        }

        // 2. Fallback: aggregate cleaned release notes across all newer releases (newest first)
        return formatAggregatedReleaseBodies(newerReleases, allByTag)
    }

    private fun fetchCommitsBetween(
        installedVersion: String,
        latestTag: String,
        allByTag: Map<String, JSONObject>
    ): List<CommitItem>? {
        val isBundle = latestTag.endsWith("-all")
        val plainLatestTag = latestTag.removeSuffix("-all")
        val cleanVer = installedVersion.trim().removePrefix("v").removeSuffix("-all")
        val hasBuildNum = Regex("-mod\\.\\d+").containsMatchIn(cleanVer)
        val cleanHash = currentGitHash.substringBefore('-').trim()

        val candidates = ArrayList<Pair<String, String>>()
        if (hasBuildNum) {
            if (isBundle) {
                candidates += "v$cleanVer-all" to latestTag
            }
            candidates += "v$cleanVer" to plainLatestTag
        }
        if (cleanHash.length >= 7) {
            if (isBundle) {
                candidates += cleanHash to latestTag
            }
            if (plainLatestTag.isNotEmpty() && (!isBundle || allByTag.containsKey(plainLatestTag))) {
                candidates += cleanHash to plainLatestTag
            }
        }

        for ((base, head) in candidates.distinct()) {
            if (base == head) continue
            val parsed = runCatching { queryCompareCommits(base, head) }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    private fun queryCompareCommits(base: String, head: String): List<CommitItem>? {
        val encBase = URLEncoder.encode(base, "UTF-8")
        val encHead = URLEncoder.encode(head, "UTF-8")
        val json = JSONObject(httpGetJson("https://api.github.com/repos/$repo/compare/$encBase...$encHead"))
        val status = json.optString("status")
        val behindBy = json.optInt("behind_by", 0)
        // Reject cross-branch comparisons where base is not an ancestor (e.g. master sha vs all-in-one tag)
        if (status != "ahead" && !(status == "diverged" && behindBy <= 3)) {
            return null
        }
        val arr = json.optJSONArray("commits") ?: return null
        val out = ArrayList<CommitItem>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val parents = item.optJSONArray("parents")
            if (parents != null && parents.length() > 1) continue // skip merge commits
            val sha = item.optString("sha")
            val commitObj = item.optJSONObject("commit") ?: continue
            val rawMsg = commitObj.optString("message").lineSequence().firstOrNull()?.trim().orEmpty()
            if (rawMsg.isEmpty() || rawMsg.startsWith("Merge ")) continue
            val subject = rawMsg.replace("[skip ci]", "").trim()
            if (subject.isEmpty()) continue
            val isoDate = commitObj.optJSONObject("committer")?.optString("date")
                ?: commitObj.optJSONObject("author")?.optString("date")
                ?: ""
            out += CommitItem(sha = sha, subject = subject, date = formatLocalDay(isoDate))
        }
        return out
    }

    private fun formatGroupedCommits(
        commitsOldestFirst: List<CommitItem>,
        newerReleasesNewestFirst: List<CandidateRelease>,
        allFetchedNewer: Boolean,
        allByTag: Map<String, JSONObject>
    ): String {
        if (newerReleasesNewestFirst.isEmpty()) {
            return buildString {
                appendLine("更新日志：")
                commitsOldestFirst.asReversed().forEach { c ->
                    val prefix = if (c.date.isNotEmpty()) "${c.date}  " else ""
                    appendLine("• $prefix${c.subject}")
                }
            }.trim()
        }

        // Map target SHA -> release index (oldest-to-newest order)
        val chronologicalReleases = newerReleasesNewestFirst.asReversed()
        val shaToReleaseIdx = HashMap<String, Int>()
        chronologicalReleases.forEachIndexed { idx, rel ->
            if (rel.targetCommitish.length >= 7) {
                shaToReleaseIdx[rel.targetCommitish] = idx
            }
            val plain = allByTag[rel.tag.removeSuffix("-all")]?.optString("target_commitish").orEmpty()
            if (plain.length >= 7) {
                shaToReleaseIdx[plain] = idx
            }
        }

        val buckets = Array(chronologicalReleases.size) { ArrayList<CommitItem>() }
        val unmatchedBeforeOldest = ArrayList<CommitItem>()
        val pending = ArrayList<CommitItem>()
        var minAssignedIdx = -1

        for (c in commitsOldestFirst) {
            pending += c
            val matchedIdx = shaToReleaseIdx.entries.firstOrNull { (targetSha, idx) ->
                idx > minAssignedIdx && (c.sha.equals(targetSha, ignoreCase = true) ||
                        c.sha.startsWith(targetSha, ignoreCase = true) ||
                        targetSha.startsWith(c.sha, ignoreCase = true))
            }?.value
            if (matchedIdx != null) {
                if (matchedIdx == 0 && allFetchedNewer && pending.size > 1) {
                    unmatchedBeforeOldest.addAll(pending.subList(0, pending.lastIndex))
                    buckets[0].add(pending.last())
                } else {
                    buckets[matchedIdx].addAll(pending)
                }
                pending.clear()
                minAssignedIdx = matchedIdx
            }
        }
        if (pending.isNotEmpty()) {
            buckets[buckets.lastIndex].addAll(pending)
            pending.clear()
        }

        // Also check if the first matched bucket absorbed commits older than the oldest fetched release
        val activeSections = ArrayList<Pair<String, List<String>>>()
        for (idx in chronologicalReleases.indices.reversed()) {
            val rel = chronologicalReleases[idx]
            val bucketCommits = buckets[idx].asReversed()
            val customNotes = extractCustomNotes(rel, allByTag)
            if (bucketCommits.isEmpty() && customNotes.isEmpty()) continue
            val day = formatLocalDay(rel.publishedAt)
            val displayTag = rel.tag.removeSuffix("-all")
            val header = if (day.isNotEmpty()) "$displayTag ($day)" else displayTag
            val lines = ArrayList<String>()
            customNotes.forEach { lines += "• $it" }
            bucketCommits.forEach { c ->
                if (customNotes.none { it.contains(c.subject) }) {
                    lines += "• ${c.subject}"
                }
            }
            if (lines.isNotEmpty()) {
                activeSections += header to lines
            }
        }
        if (unmatchedBeforeOldest.isNotEmpty()) {
            activeSections += "更早更新" to unmatchedBeforeOldest.asReversed().map { "• ${it.subject}" }
        }

        if (activeSections.isEmpty()) {
            return formatAggregatedReleaseBodies(newerReleasesNewestFirst, allByTag)
        }

        return buildString {
            val countLabel = if (activeSections.size > 1) "（当前版本之后共 ${activeSections.size} 个版本）" else ""
            appendLine("更新日志$countLabel：")
            activeSections.forEach { (header, lines) ->
                appendLine()
                appendLine(header)
                lines.forEach { appendLine(it) }
            }
        }.trim()
    }

    private fun extractCustomNotes(
        rel: CandidateRelease,
        allByTag: Map<String, JSONObject>
    ): List<String> {
        val plainBody = allByTag[rel.tag.removeSuffix("-all")]?.optString("body").orEmpty()
        return (extractMeaningfulLines(rel.rawBody, includeAutoCommit = false) +
                extractMeaningfulLines(plainBody, includeAutoCommit = false)).distinct()
    }

    private fun formatAggregatedReleaseBodies(
        newerReleasesNewestFirst: List<CandidateRelease>,
        allByTag: Map<String, JSONObject>
    ): String {
        if (newerReleasesNewestFirst.isEmpty()) return ""
        val sections = ArrayList<Pair<String, List<String>>>()
        for (rel in newerReleasesNewestFirst) {
            val plainBody = allByTag[rel.tag.removeSuffix("-all")]?.optString("body").orEmpty()
            val lines = extractMeaningfulLines(rel.rawBody, includeAutoCommit = true).ifEmpty {
                extractMeaningfulLines(plainBody, includeAutoCommit = true)
            }
            if (lines.isEmpty()) continue
            val day = formatLocalDay(rel.publishedAt)
            val displayTag = rel.tag.removeSuffix("-all")
            val header = if (day.isNotEmpty()) "$displayTag ($day)" else displayTag
            sections += header to lines.map { "• $it" }
        }
        if (sections.isEmpty()) return newerReleasesNewestFirst.first().rawBody.trim()
        return buildString {
            val countLabel = if (sections.size > 1) "（当前版本之后共 ${sections.size} 个版本）" else ""
            appendLine("更新日志$countLabel：")
            sections.forEach { (header, lines) ->
                appendLine()
                appendLine(header)
                lines.forEach { appendLine(it) }
            }
        }.trim()
    }

    private fun extractMeaningfulLines(body: String, includeAutoCommit: Boolean): List<String> {
        if (body.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("修改内容见") || line.startsWith("说明见")) continue
            if (line.startsWith("- 只支持") || line.startsWith("- 包名 ") ||
                line.startsWith("- 插件需和") || line.startsWith("- 不需要也不会")
            ) continue
            if (line.contains("自动编译：")) {
                if (!includeAutoCommit) continue
                val after = line.substringAfter("自动编译：").trim()
                // Strip leading short git hash if present (e.g. "06a0adfe Unify Liquid Glass...")
                val cleaned = after.replace(Regex("^[0-9a-f]{7,40}\\s+"), "").trim()
                if (cleaned.isNotEmpty() && !cleaned.startsWith("Merge ")) {
                    out += cleaned
                }
                continue
            }
            if (line.startsWith("全家桶：")) continue
            out += line.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
        }
        return out
    }

    private fun formatLocalDay(isoUtc: String): String {
        if (isoUtc.length < 10) return ""
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(isoUtc) ?: return@runCatching isoUtc.take(10)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        }.getOrDefault(isoUtc.take(10))
    }

    /** 打开 App 时调用：一天最多查一次，有新版本才弹窗 */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0) < 20 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        val currentVer = currentVersion(activity)
        Thread {
            val rel = runCatching { fetchLatest(currentVer) }.getOrNull() ?: return@Thread
            if (!isNewer(rel.tag, currentVer) || rel.tag == prefs.getString("skipped", null)) return@Thread
            main.post { if (!activity.isFinishing && !activity.isDestroyed) showRelease(activity, rel, auto = true) }
        }.start()
    }

    /** 设置里的“检查更新” */
    fun checkNow(activity: Activity) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        val currentVer = currentVersion(activity)
        Thread {
            val result = runCatching { fetchLatest(currentVer) }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val rel = result.getOrElse {
                    Toast.makeText(activity, "检查更新失败：${it.message ?: it.javaClass.simpleName}（访问 GitHub 可能需要代理）", Toast.LENGTH_LONG).show()
                    return@post
                }
                if (rel == null || !isNewer(rel.tag, currentVer)) {
                    Toast.makeText(activity, "已是最新版本（$currentVer）", Toast.LENGTH_SHORT).show()
                } else showRelease(activity, rel, auto = false)
            }
        }.start()
    }

    private fun showRelease(activity: Activity, rel: Release, auto: Boolean) {
        val notes = rel.notes.trim().let { if (it.length > 4000) it.take(4000) + "\n…" else it }
        val sizePart = if (rel.size > 0) "  ·  安装包 %.1f MB".format(rel.size / 1048576.0) else ""
        val header = "当前版本：${currentVersion(activity)}$sizePart"
        val msg = if (notes.isNotEmpty()) "$header\n\n$notes" else header
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${rel.tag}")
            .setMessage(msg)
            .setPositiveButton("下载并安装") { _, _ -> download(activity, rel) }
            .setNegativeButton("以后再说", null)
            .apply {
                if (auto) {
                    setNeutralButton("跳过此版本") { _, _ ->
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", rel.tag).apply()
                    }
                } else if (rel.page.isNotBlank()) {
                    setNeutralButton("查看网页") { _, _ -> openPage(activity, rel.page) }
                }
            }
            .show()
    }

    private fun download(activity: Activity, rel: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = rel.size <= 0 }
        val label = TextView(activity).apply { text = "正在下载 ${rel.fileName}" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label); addView(bar)
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("下载更新")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .show()
        Thread {
            val dir = File(activity.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
            val file = File(dir, rel.fileName)
            val result = runCatching {
                val conn = URL(rel.url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) throw java.io.IOException("下载失败 ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
                    var done = 0L
                    var shown = -1
                    conn.inputStream.use { ins ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) throw InterruptedException()
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != shown) {
                                    shown = pct
                                    main.post { if (pct >= 0) { bar.isIndeterminate = false; bar.progress = pct }; label.text = "正在下载 ${rel.fileName}  ${if (pct >= 0) "$pct%" else ""}" }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                // 核对包名：下错了（别的 App、别的版本线）就不装
                val info = activity.packageManager.getPackageArchiveInfo(file.path, 0)
                if (info?.packageName != activity.packageName) throw IllegalStateException("安装包不是这个 App 的（${info?.packageName ?: "无法读取"}）")
                file
            }
            main.post {
                runCatching { dialog.dismiss() }
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { install(activity, it) }.onFailure { e ->
                    if (e is InterruptedException) return@onFailure
                    AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage((e.message ?: e.javaClass.simpleName) + "\n\n访问 GitHub 可能需要代理；也可以到网页上手动下载。")
                        .setPositiveButton("去网页下载") { _, _ -> openPage(activity, rel.page) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }.start()
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("需要允许安装应用")
                .setMessage("第一次更新要允许这个 App“安装未知应用”。打开设置开启后返回，再点“安装”。")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }
                    // 回来后接着装
                    AlertDialog.Builder(activity)
                        .setMessage("开启后点“安装”。")
                        .setPositiveButton("安装") { _, _ -> install(activity, file) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update", file)
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { Toast.makeText(activity, "无法打开安装程序：${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun openPage(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        private const val PREFS = "app_update"
        private const val DIR = "updates"

        /** 版本号里的数字依次比较：v1.10.0 > 1.9.2，v0.1.3-mod.91 > 0.1.3-mod.89，0.1.3-mod.91 > 0.1.3-mod */
        fun isNewer(remote: String, current: String): Boolean {
            val a = Regex("\\d+").findAll(remote).map { it.value.toLong() }.toList()
            val b = Regex("\\d+").findAll(current).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** 这台手机能用的 ABI，按系统推荐的顺序 */
        val abis: List<String> get() = Build.SUPPORTED_ABIS.toList()
    }
}

/**
 * 修改版的更新来源：GitHub 上 clusflah/fcitx5-android-mod 的 Release。
 *  - 普通版（包名 .mod）：v0.1.3-mod.N 里的 org.fcitx.fcitx5.android-…-<架构>-release.apk（插件不在这里更新）
 *  - 全家桶（包名 .mod.all）：v0.1.3-mod.N-all 里的 fcitx5-all-in-one-…-<架构>.apk
 * 原版包名（自己编译的调试版等）没有对应的发布，不检查。
 */
object ModUpdate {
    fun supported(context: Context) = context.packageName.endsWith(".mod") || context.packageName.endsWith(".mod.all")

    private fun bundle(context: Context) = context.packageName.endsWith(".mod.all")

    fun updater(context: Context): AppUpdater {
        val all = bundle(context)
        return AppUpdater(
            repo = "clusflah/fcitx5-android-mod",
            acceptTag = { it.endsWith("-all") == all },
            pickAsset = { names ->
                AppUpdater.abis.firstNotNullOfOrNull { abi ->
                    names.firstOrNull {
                        if (all) it.startsWith("fcitx5-all-in-one-") && it.endsWith("-$abi.apk")
                        else it.startsWith("org.fcitx.fcitx5.android-") && it.endsWith("-$abi-release.apk")
                    }
                }
            },
            currentGitHash = BuildConfig.BUILD_GIT_HASH,
        )
    }
}
