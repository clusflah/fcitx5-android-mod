/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.ai.AiTranslate
import org.fcitx.fcitx5.android.data.ai.OfflineTranslator
import org.fcitx.fcitx5.android.data.ai.TranslateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslateTest {

    @Test
    fun direction() {
        assertTrue(AiTranslate.looksChinese("今天的弹幕好多啊"))
        assertTrue(AiTranslate.looksChinese("明天下午3点在 Starbucks 见"))   // mostly Chinese
        assertFalse(AiTranslate.looksChinese("See you at 3pm tomorrow"))
        assertFalse(AiTranslate.looksChinese("今日はいい天気ですね"))         // kana → Japanese
        assertFalse(AiTranslate.looksChinese("12345"))
    }

    @Test
    fun offlineTranslatorCodes() {
        // every target language offered in settings, plus Simplified Chinese, has a code
        (AiTranslate.TARGETS + "简体中文").forEach {
            assertTrue("no code for $it", OfflineTranslator.codeOf(it) != null)
        }
        assertEquals("zh", OfflineTranslator.codeOf("简体中文"))
        assertEquals("zh_hant", OfflineTranslator.codeOf("繁体中文"))
        assertEquals(TranslateEngine.Offline, TranslateEngine.of("offline_translator"))
    }

    @Test
    fun engines() {
        assertEquals(TranslateEngine.IndexTranslate, TranslateEngine.of(null))
        assertEquals(TranslateEngine.IndexTranslate, TranslateEngine.of("removed_engine"))
        assertEquals("DeepSeek", (TranslateEngine.of("DeepSeek") as TranslateEngine.Provider).provider.name)
    }
}
