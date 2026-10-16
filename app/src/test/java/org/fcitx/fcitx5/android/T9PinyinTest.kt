/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.T9Pinyin
import org.junit.Assert.assertEquals
import org.junit.Test

class T9PinyinTest {

    @Test
    fun hintThenSpelledRest() {
        // 木乃伊: "mu nai" from the candidate, the trailing 9 spelled as a letter
        assertEquals("mu'nai'y", T9Pinyin.displayPreedit("686249", "mu nai"))
    }

    @Test
    fun noHintStillPinyin() {
        // 今天就好 typed as 546 8426 548 426, first candidate without a pinyin hint
        val shown = T9Pinyin.displayPreedit("5468 426 54 8426", null)!!
        assert(shown.none { it in '0'..'9' }) { shown }
    }

    @Test
    fun hintNotMatchingFallsBackToSpelling() {
        val shown = T9Pinyin.displayPreedit("5468426548426", "liu gan ji")!!
        assert(shown.none { it in '0'..'9' }) { shown }
    }

    @Test
    fun chosenLettersKept() {
        assertEquals("ni'hao", T9Pinyin.displayPreedit("ni426", null))
    }

    @Test
    fun committedHeadKept() {
        assertEquals("你hao", T9Pinyin.displayPreedit("你426", null))
    }
}
