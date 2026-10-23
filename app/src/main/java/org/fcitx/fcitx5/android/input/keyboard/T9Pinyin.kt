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

    /**
     * Like [pendingInput], but also when every syllable has been chosen already (no digits left):
     * the code still in Rime's input, or null when there is none.
     */
    fun typedInput(preedit: String): String? {
        val tail = preedit.takeLastWhile { it == ' ' || it == '\'' || it in 'a'..'z' || it in '0'..'9' }
        return tail.replace(" ", "").takeIf { raw -> raw.any { it in 'a'..'z' || it in '2'..'9' } }
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

    /**
     * A candidate's pinyin hint is not shown beside it: on the 九宫格 it goes to the preedit line,
     * and on the full keyboard the typed pinyin is already in the preedit line.
     */
    fun hidesComment(comment: String) = PINYIN_HINT.matches(comment.trim().lowercase())

    /**
     * The preedit line for the 九宫格: the digits still to be converted are replaced by the pinyin of
     * the first candidate (Rime's spelling hint in its comment, e.g. "ni hao"), so "你64426" shows as
     * "你ni'hao". Digits the candidate doesn't cover stay as they are. Null = show [preedit] unchanged.
     */
    fun displayPreedit(preedit: String, firstComment: String?): String? =
        displayPreedit(preedit, preedit.length, firstComment)?.first

    /**
     * [displayPreedit] plus where Rime's caret ([cursor], an index into [preedit]) falls in the
     * shown text: each digit or letter of the input is one letter of the pinyin, so moving the caret
     * with ← → (e.g. by swiping on space) moves it through the pinyin too.
     */
    fun displayPreedit(preedit: String, cursor: Int, firstComment: String?): Pair<String, Int>? {
        val raw = pendingInput(preedit) ?: return null
        val tail = preedit.takeLastWhile { it == ' ' || it == '\'' || it in 'a'..'z' || it in '0'..'9' }
        val head = preedit.dropLast(tail.length)
        // walk the input syllable by syllable; letters already chosen encode to themselves
        val input = raw.replace("'", "")
        val code = encode(input)
        var pos = 0
        val shown = mutableListOf<String>()
        val hint = firstComment?.trim()?.lowercase()?.takeIf { PINYIN_HINT.matches(it) }
        if (hint != null) for (syllable in hint.split(' ', '\'')) {
            val c = encode(syllable.replace('ü', 'v'))
            if (!code.startsWith(c, pos)) break
            shown.add(syllable)
            pos += c.length
        }
        // what the candidate doesn't cover (or all of it, when it has no pinyin hint): spell it
        // out ourselves, so the line never falls back to digits
        val rest = input.substring(pos)
        if (rest.isNotEmpty()) shown.addAll(spell(rest) ?: listOf(rest))
        val text = head + shown.joinToString("'")
        if (cursor <= head.length) return text to cursor.coerceAtLeast(0)
        // letters of the input before the caret, then the same number of pinyin letters
        var letters = tail.take(cursor - head.length).count { it != ' ' && it != '\'' }
        var at = head.length
        for ((i, piece) in shown.withIndex()) {
            if (i > 0) at++ // the ' between syllables
            if (letters <= piece.length) return text to at + letters
            letters -= piece.length
            at += piece.length
        }
        return text to text.length
    }

    /** rough frequency order of syllables, to pick one when several share the same digits */
    private val COMMON = ("de shi yi bu you zhe ge le wo ren zai ta men lai dao shang da wei ji zi " +
        "zhong guo sheng xia yu jia ke zhi xue neng jiu me hui nian dui fa chu zuo yao ye kan dong " +
        "ying jin hao xiang qi mei jian yong li ru shuo dian qian ming gong bei ri zhu ben xin hou " +
        "fang tian he cheng gao zheng shou lu bian mian xing qu xian quan ai du er hai ma ni na hen " +
        "ba ting zou dan wen ci jing xie bie zhao an mu kai jie ji tong xiao yan shen ying ke lian " +
        "san si wu liu qing ba jiao zhan chang yang jiang hua kuai gei qie han ru ning bao liang " +
        "duo shao ming tou ying zui ce zao wan hong yue bai lao gan ji sui ge shu che pin yin")
        .split(' ').withIndex().reversed().associate { (i, s) -> s to i }

    private fun rank(syllable: String) = COMMON[syllable] ?: (1000 + syllable.length)

    /** whether [syllable] fits [input] at [at]: digits stand for any of their letters, letters for themselves */
    private fun fits(input: String, at: Int, syllable: String): Boolean {
        if (at + syllable.length > input.length) return false
        return syllable.indices.all { k ->
            val c = input[at + k]
            c == syllable[k] || c == DIGIT_OF[syllable[k]]
        }
    }

    /**
     * Pinyin for typed digits (and letters already chosen): the fewest syllables that cover it,
     * favouring common ones; an unfinished last syllable shows as its first letters. Null if the
     * input can't be read as pinyin (e.g. it has 0 or 1 in it).
     */
    fun spell(input: String): List<String>? {
        val n = input.length
        // best[i] = (syllables, rank sum, first piece) for input[i:]
        val best = arrayOfNulls<Triple<Int, Int, String>>(n + 1)
        best[n] = Triple(0, 0, "")
        for (i in n - 1 downTo 0) {
            var choice: Triple<Int, Int, String>? = null
            fun offer(piece: String, next: Triple<Int, Int, String>) {
                val c = Triple(next.first + 1, next.second + rank(piece), piece)
                if (choice == null || c.first < choice!!.first ||
                    (c.first == choice!!.first && c.second < choice!!.second)) choice = c
            }
            for ((s, _) in SYLLABLES) {
                if (!fits(input, i, s)) continue
                best[i + s.length]?.let { offer(s, it) }
            }
            // the last, unfinished syllable: letters of the most common syllable it could become
            if (choice == null) {
                val left = n - i
                SYLLABLES.filter { (s, _) -> s.length > left && fits(input, i, s.take(left)) }
                    .minByOrNull { rank(it.first) }
                    ?.let { offer(it.first.take(left), best[n]!!) }
            }
            best[i] = choice
        }
        if (best[0] == null) return null
        val out = mutableListOf<String>()
        var i = 0
        while (i < n) {
            val piece = best[i]!!.third
            out.add(piece)
            i += piece.length
        }
        return out
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
