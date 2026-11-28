/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.input.preedit.PreeditTone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class PreeditToneTest {

    private lateinit var table: Map<Int, IntArray>

    @Before
    fun loadRealTable() {
        val candidates = listOf(
            File("build/intermediates/assets/release/mergeReleaseAssets/usr/share/fcitx5/pinyinhelper/py_table.mb"),
            File("build/intermediates/assets/debug/mergeDebugAssets/usr/share/fcitx5/pinyinhelper/py_table.mb"),
            File("app/build/intermediates/assets/release/mergeReleaseAssets/usr/share/fcitx5/pinyinhelper/py_table.mb")
        )
        val file = candidates.firstOrNull { it.isFile }
        table = if (file != null) {
            PreeditTone.parseTable(file.readBytes())
        } else {
            // Minimal fallback table if py_table.mb hasn't been merged yet
            mapOf(
                '你'.code to intArrayOf((12 shl 16) or (11 shl 8) or 3), // ni3
                '好'.code to intArrayOf((8 shl 16) or (5 shl 8) or 3),  // hao3
                '中'.code to intArrayOf((24 shl 16) or (25 shl 8) or 1), // zhong1
                '国'.code to intArrayOf((7 shl 16) or (36 shl 8) or 2), // guo2
                '行'.code to intArrayOf(
                    (8 shl 16) or (4 shl 8) or 2,  // hang2
                    (21 shl 16) or (18 shl 8) or 2 // xing2
                ),
                '女'.code to intArrayOf((12 shl 16) or (37 shl 8) or 3), // nü3
                '绿'.code to intArrayOf((10 shl 16) or (37 shl 8) or 4), // lü4
                '略'.code to intArrayOf((10 shl 16) or (39 shl 8) or 4), // lüe4
                '的'.code to intArrayOf((4 shl 16) or (6 shl 8) or 0),   // de0
                '股'.code to intArrayOf((7 shl 16) or (27 shl 8) or 3),  // gu3
                '男'.code to intArrayOf((12 shl 16) or (3 shl 8) or 2)   // nan2
            )
        }
        PreeditTone.setTableForTest(table)
    }

    @After
    fun cleanup() {
        PreeditTone.setTableForTest(null)
    }

    @Test
    fun basicApostropheAndSpaceSeparators() {
        assertEquals("nǐ'hǎo", PreeditTone.applyToText("ni'hao", "你好", table))
        assertEquals("nǐ hǎo", PreeditTone.applyToText("ni hao", "你好", table))
        assertEquals("zhōng guó", PreeditTone.applyToText("zhong guo", "中国", table))
    }

    @Test
    fun committedChinesePrefixPreserved() {
        assertEquals("你hǎo", PreeditTone.applyToText("你hao", "好", table))
        assertEquals("你 hǎo", PreeditTone.applyToText("你 hao", "好", table))
    }

    @Test
    fun polyphoneDisambiguatedByTypedPinyin() {
        assertEquals("xíng", PreeditTone.applyToText("xing", "行", table))
        assertEquals("háng", PreeditTone.applyToText("hang", "行", table))
    }

    @Test
    fun vNormalizedToUmlautWithTone() {
        assertEquals("nǚ", PreeditTone.applyToText("nv", "女", table))
        assertEquals("lǜ", PreeditTone.applyToText("lv", "绿", table))
        assertEquals("lüè", PreeditTone.applyToText("lve", "略", table))
    }

    @Test
    fun incompleteAndShortPinyinUntouched() {
        assertEquals("n'hǎo", PreeditTone.applyToText("n'hao", "你好", table))
        assertEquals("nǐ'hǎo'sh", PreeditTone.applyToText("ni'hao'sh", "你好", table))
        assertEquals("zh", PreeditTone.applyToText("zh", "中", table))
    }

    @Test
    fun fuzzyPinyinGetsTone() {
        assertEquals("zōng'guó", PreeditTone.applyToText("zong'guo", "中国", table))
        assertEquals("lán", PreeditTone.applyToText("lan", "男", table))
    }

    @Test
    fun nonChineseAndQuickphraseUntouched() {
        assertEquals(";nihao", PreeditTone.applyToText(";nihao", "你好", table))
        assertEquals("hello", PreeditTone.applyToText("hello", "hello", table))
        assertEquals("a'gǔ", PreeditTone.applyToText("a'gu", "A股", table))
    }

    @Test
    fun uppercaseCasePreserved() {
        assertEquals("Nǐ'hǎo", PreeditTone.applyToText("Ni'hao", "你好", table))
    }

    @Test
    fun unsegmentedTokenSupported() {
        assertEquals("nǐhǎo", PreeditTone.applyToText("nihao", "你好", table))
    }

    @Test
    fun formattedTextSegmentsAndCursorPreserved() {
        val ft = FormattedText(arrayOf("你", "h", "ao"), intArrayOf(0, 8, 16), 2)
        val toned = PreeditTone.apply(ft, "好")
        assertEquals(listOf("你", "h", "ǎo"), toned.strings.toList())
        assertEquals(listOf(0, 8, 16), toned.flags.toList())
        assertEquals(2, toned.cursor)
    }
}
