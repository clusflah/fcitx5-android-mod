/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * "打开链接" for the clipboard suggestion: find an http(s) link in copied text.
 */
package org.fcitx.fcitx5.android.data.clipboard

object ClipboardLink {

    /** stops at whitespace, quotes, brackets and Chinese punctuation that often follows a pasted link */
    private val URL = Regex("""https?://[^\s<>"'`，。！？；、（）【】《》「」]+""", RegexOption.IGNORE_CASE)

    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /** The first http(s) link in [text], without trailing punctuation; null if there is none. */
    fun find(text: String): String? =
        URL.find(text)?.value?.trimEnd(*TRAILING)?.takeIf { it.length > "https://".length }

    /** "https://www.github.com/x" → "github.com" */
    fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').removePrefix("www.")
}
