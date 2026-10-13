/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.ClipboardLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkTest {

    @Test
    fun findsLinks() {
        assertEquals("https://github.com/fcitx5-android", ClipboardLink.find("https://github.com/fcitx5-android"))
        assertEquals(
            "https://example.com/a?b=1&c=2#x",
            ClipboardLink.find("看这个 https://example.com/a?b=1&c=2#x 很有用")
        )
        // shared text from Chinese apps: link followed by Chinese punctuation
        assertEquals("https://b23.tv/AbCd12", ClipboardLink.find("【标题】https://b23.tv/AbCd12，复制后打开"))
        assertEquals("http://192.168.1.1:8080/admin", ClipboardLink.find("(http://192.168.1.1:8080/admin)."))
    }

    @Test
    fun ignoresNonWebLinks() {
        assertNull(ClipboardLink.find("javascript:alert(1)"))
        assertNull(ClipboardLink.find("file:///sdcard/a.txt"))
        assertNull(ClipboardLink.find("just some text"))
        assertNull(ClipboardLink.find("https://"))
    }

    @Test
    fun host() {
        assertEquals("github.com", ClipboardLink.host("https://www.github.com/x/y"))
        assertEquals("192.168.1.1:8080", ClipboardLink.host("http://192.168.1.1:8080/admin"))
        assertEquals("example.com", ClipboardLink.host("https://user@example.com?x=1"))
    }
}
