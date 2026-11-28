/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: add tone marks to the composing pinyin in the preedit bar using the first candidate
 * and fcitx5-chinese-addons's bundled py_table.mb.
 */
package org.fcitx.fcitx5.android.input.preedit

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object PreeditTone {

    private const val PY_TABLE_REL_PATH = "usr/share/fcitx5/pinyinhelper/py_table.mb"

    // Must stay in sync with fcitx5-chinese-addons/modules/pinyinhelper/pinyinlookup.cpp
    private val KONSONANTS = arrayOf(
        "", "b", "c", "ch", "d", "f", "g", "h", "j", "k", "l", "m", "n",
        "ng", "p", "q", "r", "s", "sh", "t", "w", "x", "y", "z", "zh"
    )

    private val VOKALS = arrayOf(
        arrayOf("", "", "", "", ""),
        arrayOf("a", "ā", "á", "ǎ", "à"),
        arrayOf("ai", "āi", "ái", "ǎi", "ài"),
        arrayOf("an", "ān", "án", "ǎn", "àn"),
        arrayOf("ang", "āng", "áng", "ǎng", "àng"),
        arrayOf("ao", "āo", "áo", "ǎo", "ào"),
        arrayOf("e", "ē", "é", "ě", "è"),
        arrayOf("ei", "ēi", "éi", "ěi", "èi"),
        arrayOf("en", "ēn", "én", "ěn", "èn"),
        arrayOf("eng", "ēng", "éng", "ěng", "èng"),
        arrayOf("er", "ēr", "ér", "ěr", "èr"),
        arrayOf("i", "ī", "í", "ǐ", "ì"),
        arrayOf("ia", "iā", "iá", "iǎ", "ià"),
        arrayOf("ian", "iān", "ián", "iǎn", "iàn"),
        arrayOf("iang", "iāng", "iáng", "iǎng", "iàng"),
        arrayOf("iao", "iāo", "iáo", "iǎo", "iào"),
        arrayOf("ie", "iē", "ié", "iě", "iè"),
        arrayOf("in", "īn", "ín", "ǐn", "ìn"),
        arrayOf("ing", "īng", "íng", "ǐng", "ìng"),
        arrayOf("iong", "iōng", "ióng", "iǒng", "iòng"),
        arrayOf("iu", "iū", "iú", "iǔ", "iù"),
        arrayOf("m", "m", "m", "m", "m"),
        arrayOf("n", "n", "ń", "ň", "ǹ"),
        arrayOf("ng", "ng", "ńg", "ňg", "ǹg"),
        arrayOf("o", "ō", "ó", "ǒ", "ò"),
        arrayOf("ong", "ōng", "óng", "ǒng", "òng"),
        arrayOf("ou", "ōu", "óu", "ǒu", "òu"),
        arrayOf("u", "ū", "ú", "ǔ", "ù"),
        arrayOf("ua", "uā", "uá", "uǎ", "uà"),
        arrayOf("uai", "uāi", "uái", "uǎi", "uài"),
        arrayOf("uan", "uān", "uán", "uǎn", "uàn"),
        arrayOf("uang", "uāng", "uáng", "uǎng", "uàng"),
        arrayOf("ue", "uē", "ué", "uě", "uè"),
        arrayOf("ueng", "uēng", "uéng", "uěng", "uèng"),
        arrayOf("ui", "uī", "uí", "uǐ", "uì"),
        arrayOf("un", "ūn", "ún", "ǔn", "ùn"),
        arrayOf("uo", "uō", "uó", "uǒ", "uò"),
        arrayOf("ü", "ǖ", "ǘ", "ǚ", "ǜ"),
        arrayOf("üan", "üān", "üán", "üǎn", "üàn"),
        arrayOf("üe", "üē", "üé", "üě", "üè"),
        arrayOf("ün", "ǖn", "ǘn", "ǚn", "ǜn")
    )

    val enabled: Boolean
        get() = AppPrefs.getInstance().keyboard.preeditPinyinTone.getValue()

    @Volatile
    private var table: Map<Int, IntArray>? = null

    private val loading = AtomicBoolean(false)
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    /**
     * Loads py_table.mb once on a background thread. If not yet ready, [onLoaded] is posted
     * on the main thread once loading completes.
     */
    fun ensureLoaded(onLoaded: (() -> Unit)? = null): Boolean {
        if (table != null) return true
        if (onLoaded != null) {
            synchronized(pendingCallbacks) {
                if (table != null) return true
                pendingCallbacks.add(onLoaded)
            }
        }
        if (loading.compareAndSet(false, true)) {
            val mainHandler = Handler(Looper.getMainLooper())
            Thread({
                val loaded = runCatching {
                    val file = File(DataManager.dataDir, PY_TABLE_REL_PATH)
                    val bytes = if (file.isFile) {
                        file.readBytes()
                    } else {
                        appContext.assets.open(PY_TABLE_REL_PATH).use { it.readBytes() }
                    }
                    parseTable(bytes)
                }.onFailure {
                    Timber.w(it, "Failed to load py_table.mb")
                }.getOrNull()
                if (loaded != null) {
                    table = loaded
                    val callbacks = synchronized(pendingCallbacks) {
                        pendingCallbacks.toList().also { pendingCallbacks.clear() }
                    }
                    if (callbacks.isNotEmpty()) {
                        mainHandler.post { callbacks.forEach { it() } }
                    }
                } else {
                    synchronized(pendingCallbacks) { pendingCallbacks.clear() }
                    loading.set(false)
                }
            }, "PreeditToneLoader").apply { isDaemon = true }.start()
        }
        return false
    }

    @VisibleForTesting
    internal fun setTableForTest(map: Map<Int, IntArray>?) {
        table = map
    }

    /**
     * Parses fcitx5-chinese-addons's binary `py_table.mb` into a compact code-point map.
     * Each packed entry stores `(consonant shl 16) or (vocal shl 8) or tone`.
     */
    internal fun parseTable(bytes: ByteArray): HashMap<Int, IntArray> {
        val map = HashMap<Int, IntArray>(49152)
        var i = 0
        val n = bytes.size
        while (i < n) {
            val wordLen = bytes[i++].toInt() and 0xFF
            if (wordLen == 0 || wordLen > 6 || i + wordLen >= n) break
            val cp = decodeSingleUtf8CodePoint(bytes, i, wordLen)
            i += wordLen
            val count = bytes[i++].toInt() and 0xFF
            if (count == 0) continue
            if (i + count * 3 > n) break
            if (cp >= 0) {
                val existing = map[cp]
                val arr = if (existing == null) {
                    IntArray(count)
                } else {
                    existing.copyOf(existing.size + count)
                }
                var dst = existing?.size ?: 0
                for (k in 0 until count) {
                    val c = bytes[i++].toInt() and 0xFF
                    val v = bytes[i++].toInt() and 0xFF
                    val t = bytes[i++].toInt() and 0xFF
                    arr[dst++] = (c shl 16) or (v shl 8) or t
                }
                map[cp] = arr
            } else {
                i += count * 3
            }
        }
        return map
    }

    private fun decodeSingleUtf8CodePoint(bytes: ByteArray, offset: Int, len: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        return when (len) {
            1 -> if (b0 < 0x80) b0 else -1
            2 -> {
                val b1 = bytes[offset + 1].toInt() and 0xFF
                ((b0 and 0x1F) shl 6) or (b1 and 0x3F)
            }
            3 -> {
                val b1 = bytes[offset + 1].toInt() and 0xFF
                val b2 = bytes[offset + 2].toInt() and 0xFF
                ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
            }
            4 -> {
                val b1 = bytes[offset + 1].toInt() and 0xFF
                val b2 = bytes[offset + 2].toInt() and 0xFF
                val b3 = bytes[offset + 3].toInt() and 0xFF
                ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or
                    ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
            }
            else -> -1
        }
    }

    /**
     * Adds tone marks to [preedit] using [candidate] while preserving exact character lengths,
     * [FormattedText.flags], and [FormattedText.cursor].
     */
    fun apply(preedit: FormattedText, candidate: String?): FormattedText {
        if (candidate.isNullOrEmpty() || preedit.isEmpty()) return preedit
        val readings = table ?: return preedit
        val raw = preedit.toString()
        val toned = applyToText(raw, candidate, readings)
        if (toned == raw || toned.length != raw.length) return preedit
        var offset = 0
        val newStrings = Array(preedit.strings.size) { idx ->
            val len = preedit.strings[idx].length
            toned.substring(offset, offset + len).also { offset += len }
        }
        return preedit.copy(strings = newStrings)
    }

    internal fun applyToText(
        preedit: String,
        candidate: String,
        readings: Map<Int, IntArray>? = table
    ): String {
        val map = readings ?: return preedit
        if (preedit.isEmpty() || candidate.isEmpty()) return preedit

        // Find the composing pinyin tail (after any already-selected Chinese prefix)
        var tailStart = preedit.length
        while (tailStart > 0 && isPinyinOrSep(preedit[tailStart - 1])) {
            tailStart--
        }
        if (tailStart >= preedit.length) return preedit
        // Ignore ASCII prefixes like quickphrase ";nihao" or digits "123ni"
        if (tailStart > 0 && preedit[tailStart - 1].code < 0x80) return preedit

        // Extract syllable token ranges inside the composing tail
        val tokenStarts = mutableListOf<Int>()
        val tokenEnds = mutableListOf<Int>()
        var p = tailStart
        while (p < preedit.length) {
            if (isPinyinLetter(preedit[p])) {
                val start = p
                while (p < preedit.length && isPinyinLetter(preedit[p])) p++
                tokenStarts.add(start)
                tokenEnds.add(p)
            } else {
                p++
            }
        }
        if (tokenStarts.isEmpty()) return preedit

        // Extract candidate code points (skipping punctuation/spaces such as "，")
        val candCps = IntArray(candidate.codePointCount(0, candidate.length))
        var candCount = 0
        var ci = 0
        while (ci < candidate.length) {
            val cp = candidate.codePointAt(ci)
            ci += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                candCps[candCount++] = cp
            }
        }
        if (candCount == 0) return preedit

        // Special case: unsegmented single token covering multiple characters (e.g. "nihao" + "你好")
        if (tokenStarts.size == 1 && candCount > 1) {
            val start = tokenStarts[0]
            val end = tokenEnds[0]
            val joined = toneUnsegmentedToken(preedit.substring(start, end), candCps, candCount, map)
            if (joined != null && joined.length == end - start) {
                return preedit.substring(0, start) + joined + preedit.substring(end)
            }
        }

        val sb = StringBuilder(preedit)
        var changed = false
        val limit = minOf(tokenStarts.size, candCount)
        for (idx in 0 until limit) {
            val entries = map[candCps[idx]] ?: continue
            val start = tokenStarts[idx]
            val end = tokenEnds[idx]
            val rawToken = preedit.substring(start, end)
            val toned = toneSyllable(rawToken, entries) ?: continue
            if (toned != rawToken && toned.length == rawToken.length) {
                sb.replace(start, end, toned)
                changed = true
            }
        }
        return if (changed) sb.toString() else preedit
    }

    private fun toneUnsegmentedToken(
        token: String,
        candCps: IntArray,
        candCount: Int,
        readings: Map<Int, IntArray>
    ): String? {
        val lower = token.lowercase()
        val normalized = lower.replace('v', 'ü')
        val out = StringBuilder(token.length)
        var pos = 0
        for (idx in 0 until candCount) {
            if (pos >= normalized.length) break
            val entries = readings[candCps[idx]] ?: return null
            var matchedPlain: String? = null
            var matchedToned: String? = null
            for (packed in entries) {
                val plain = plainOf(packed)
                if (plain.isNotEmpty() && normalized.startsWith(plain, pos)) {
                    // Prefer the longest matching syllable in case of ambiguity
                    if (matchedPlain == null || plain.length > matchedPlain.length) {
                        matchedPlain = plain
                        matchedToned = tonedOf(packed)
                    }
                }
            }
            if (matchedPlain == null || matchedToned == null || matchedToned.length != matchedPlain.length) {
                return null
            }
            out.append(matchCase(token.substring(pos, pos + matchedPlain.length), matchedToned))
            pos += matchedPlain.length
        }
        return if (pos == normalized.length) out.toString() else null
    }

    private fun toneSyllable(rawToken: String, entries: IntArray): String? {
        val lower = rawToken.lowercase()
        val normalized = lower.replace('v', 'ü')

        // Pass 1: exact pinyin match
        for (packed in entries) {
            val plain = plainOf(packed)
            if (plain == normalized) {
                val toned = tonedOf(packed)
                if (toned.length == rawToken.length) {
                    return matchCase(rawToken, toned)
                }
            }
        }

        // Pass 2: fuzzy pinyin match (requires a vowel so incomplete initials like "zh" stay untouched)
        if (!hasVowel(normalized)) return null
        val fuzzyTyped = fuzzyNormalize(normalized)
        for (packed in entries) {
            val plain = plainOf(packed)
            if (plain.isEmpty()) continue
            if (fuzzyNormalize(plain) == fuzzyTyped) {
                val tone = packed and 0xFF
                val base = if ('v' in lower) normalized else lower
                val toned = applyToneMark(base, tone)
                if (toned.length == rawToken.length) {
                    return matchCase(rawToken, toned)
                }
            }
        }
        return null
    }

    private fun plainOf(packed: Int): String {
        val c = (packed ushr 16) and 0xFF
        val v = (packed ushr 8) and 0xFF
        if (c >= KONSONANTS.size || v >= VOKALS.size) return ""
        return KONSONANTS[c] + VOKALS[v][0]
    }

    private fun tonedOf(packed: Int): String {
        val c = (packed ushr 16) and 0xFF
        val v = (packed ushr 8) and 0xFF
        val t = (packed and 0xFF).let { if (it in 0..4) it else 0 }
        if (c >= KONSONANTS.size || v >= VOKALS.size) return ""
        return KONSONANTS[c] + VOKALS[v][t]
    }

    private fun fuzzyNormalize(s: String): String {
        var res = s.replace('ü', 'u')
        res = when {
            res.startsWith("zh") -> "z" + res.substring(2)
            res.startsWith("ch") -> "c" + res.substring(2)
            res.startsWith("sh") -> "s" + res.substring(2)
            res.startsWith("n") -> "l" + res.substring(1)
            res.startsWith("r") -> "l" + res.substring(1)
            res.startsWith("h") -> "f" + res.substring(1)
            else -> res
        }
        if (res.endsWith("ng") && res.length > 2) {
            res = res.dropLast(1)
        }
        return res
    }

    private fun applyToneMark(syllable: String, tone: Int): String {
        if (tone !in 1..4) return syllable
        var idx = syllable.indexOf('a')
        if (idx < 0) idx = syllable.indexOf('e')
        if (idx < 0) {
            val ou = syllable.indexOf("ou")
            if (ou >= 0) idx = ou
        }
        if (idx < 0) {
            for (i in syllable.lastIndex downTo 0) {
                if (syllable[i] in "iouü") {
                    idx = i
                    break
                }
            }
        }
        if (idx < 0) return syllable
        val marked = markVowel(syllable[idx], tone) ?: return syllable
        return syllable.substring(0, idx) + marked + syllable.substring(idx + 1)
    }

    private fun markVowel(ch: Char, tone: Int): Char? = when (ch) {
        'a' -> "āáǎà"[tone - 1]
        'e' -> "ēéěè"[tone - 1]
        'i' -> "īíǐì"[tone - 1]
        'o' -> "ōóǒò"[tone - 1]
        'u' -> "ūúǔù"[tone - 1]
        'ü' -> "ǖǘǚǜ"[tone - 1]
        else -> null
    }

    private fun matchCase(original: String, toned: String): String {
        var anyUpper = false
        for (ch in original) {
            if (ch.isUpperCase()) {
                anyUpper = true
                break
            }
        }
        if (!anyUpper) return toned
        val chars = toned.toCharArray()
        for (i in chars.indices) {
            if (original[i].isUpperCase()) {
                chars[i] = chars[i].uppercaseChar()
            }
        }
        return String(chars)
    }

    private fun hasVowel(s: String): Boolean = s.any { it in "aeiouü" }

    private fun isPinyinLetter(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == 'ü' || c == 'Ü'

    private fun isPinyinOrSep(c: Char): Boolean =
        isPinyinLetter(c) || c == '\'' || c == ' '
}
