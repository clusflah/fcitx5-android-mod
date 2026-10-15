/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 九宫格拼音选择列: which pinyin syllables the digits typed so far can stand for, and the keys
 * that replace those digits with the chosen syllable in Rime's input. The nine-key schema spells
 * every syllable with digits and with letters, so "ni46" is as valid an input as "6446".
 */
package org.fcitx.fcitx5.android.input.keyboard

object T9Pinyin {

    /** finals per initial ("" = zero initial); joined they give every Mandarin syllable (ü written v) */
    private val TABLE = listOf(
        "" to "a ai an ang ao e ei en eng er o ou",
        "b" to "a ai an ang ao ei en eng i ian iao ie in ing o u",
        "p" to "a ai an ang ao ei en eng i ian iao ie in ing o ou u",
        "m" to "a ai an ang ao e ei en eng i ian iao ie in ing iu o ou u",
        "f" to "a an ang ei en eng o ou u",
        "d" to "a ai an ang ao e ei en eng i ia ian iao ie ing iu ong ou u uan ui un uo",
        "t" to "a ai an ang ao e ei eng i ian iao ie ing ong ou u uan ui un uo",
        "n" to "a ai an ang ao e ei en eng i ian iang iao ie in ing iu ong ou u uan un uo v ve",
        "l" to "a ai an ang ao e ei eng i ia ian iang iao ie in ing iu o ong ou u uan un uo v ve",
        "g" to "a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo",
        "k" to "a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo",
        "h" to "a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo",
        "j" to "i ia ian iang iao ie in ing iong iu u uan ue un",
        "q" to "i ia ian iang iao ie in ing iong iu u uan ue un",
        "x" to "i ia ian iang iao ie in ing iong iu u uan ue un",
        "zh" to "a ai an ang ao e ei en eng i ong ou u ua uai uan uang ui un uo",
        "ch" to "a ai an ang ao e en eng i ong ou u ua uai uan uang ui un uo",
        "sh" to "a ai an ang ao e ei en eng i ou u ua uai uan uang ui un uo",
        "r" to "an ang ao e en eng i ong ou u ua uan ui un uo",
        "z" to "a ai an ang ao e ei en eng i ong ou u uan ui un uo",
        "c" to "a ai an ang ao e en eng i ong ou u uan ui un uo",
        "s" to "a ai an ang ao e en eng i ong ou u uan ui un uo",
        "y" to "a an ang ao e i in ing o ong ou u uan ue un",
        "w" to "a ai an ang ei en eng o u",
    )

    private val KEYS = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )

    private val DIGIT_OF: Map<Char, Char> = KEYS.flatMap { (d, ls) -> ls.map { it to d } }.toMap()

    /** syllable → its digits, e.g. "ni" → "64" */
    private val SYLLABLES: List<Pair<String, String>> = TABLE.flatMap { (initial, finals) ->
        finals.split(' ').map { initial + it }
    }.distinct().map { it to encode(it) }

    fun encode(s: String) = s.map { DIGIT_OF[it] ?: it }.joinToString("")

    /**
     * Rime's raw input still to be converted, read from its preedit: selected words come first as
     * Chinese, the rest is the code with spaces between syllables. Null if nothing is left.
     */
    fun pendingInput(preedit: String): String? {
        val tail = preedit.takeLastWhile { it == ' ' || it == '\'' || it in 'a'..'z' || it in '0'..'9' }
        return tail.replace(" ", "").takeIf { raw -> raw.any { it in '2'..'9' } }
    }

    /** Index in [raw] of the first digit, i.e. where the syllables not chosen yet start. */
    private fun digitStart(raw: String) = raw.indexOfFirst { it in '2'..'9' }

    /**
     * Syllables the first digits can stand for: full syllables matching the most digits first,
     * then the single letters of the first key (initials / abbreviations).
     */
    fun options(raw: String, limit: Int = 40): List<String> {
        val start = digitStart(raw)
        if (start < 0) return emptyList()
        val digits = raw.substring(start).takeWhile { it in '2'..'9' }
        val full = SYLLABLES
            .filter { (_, code) -> code.length > 1 && digits.startsWith(code) }
            .sortedByDescending { it.second.length }
            .map { it.first }
        val letters = KEYS[digits[0]].orEmpty().map { it.toString() }
        return (full + letters).distinct().take(limit)
    }

    /** set while the 九宫格 is shown: the preedit line then shows pinyin instead of digits */
    @Volatile
    var active = false

    private val PINYIN_HINT = Regex("""[a-zü]+(?:[ '][a-zü]+)*""")

    /** On the 九宫格, a candidate's pinyin hint is shown in the preedit line instead of beside it. */
    fun hidesComment(comment: String) = active && PINYIN_HINT.matches(comment.trim().lowercase())

    /**
     * The preedit line for the 九宫格: the digits still to be converted are replaced by the pinyin of
     * the first candidate (Rime's spelling hint in its comment, e.g. "ni hao"), so "你64426" shows as
     * "你ni'hao". Digits the candidate doesn't cover stay as they are. Null = show [preedit] unchanged.
     */
    fun displayPreedit(preedit: String, firstComment: String?): String? {
        val hint = firstComment?.trim()?.lowercase()?.takeIf { PINYIN_HINT.matches(it) } ?: return null
        val raw = pendingInput(preedit) ?: return null
        val tail = preedit.takeLastWhile { it == ' ' || it == '\'' || it in 'a'..'z' || it in '0'..'9' }
        val head = preedit.dropLast(tail.length)
        // walk the input syllable by syllable; letters already chosen encode to themselves
        val code = encode(raw.replace("'", ""))
        var pos = 0
        val shown = mutableListOf<String>()
        for (syllable in hint.split(' ', '\'')) {
            val c = encode(syllable.replace('ü', 'v'))
            if (!code.startsWith(c, pos)) break
            shown.add(syllable)
            pos += c.length
        }
        if (shown.isEmpty()) return null
        val rest = code.substring(pos)
        return head + shown.joinToString("'") + if (rest.isEmpty()) "" else "'$rest"
    }

    /**
     * Edit that puts [syllable] in place of the digits it covers:
     * how many characters to delete from the end of the input, then what to type.
     */
    fun replacement(raw: String, syllable: String): Pair<Int, String>? {
        val start = digitStart(raw)
        if (start < 0) return null
        val covered = syllable.length
        val rest = raw.substring(start)
        if (rest.length < covered || encode(syllable) != rest.substring(0, covered)) return null
        return rest.length to (syllable + rest.substring(covered))
    }
}
