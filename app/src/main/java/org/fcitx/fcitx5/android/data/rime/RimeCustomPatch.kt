/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * A block of settings the app keeps in Rime's <schema>.custom.yaml files, between two marker
 * comments, so it can be replaced or removed without touching the user's own lines.
 * Used by RimeGrammar (万象语法模型) and RimeSimplified (简体模式只出简体字).
 */
package org.fcitx.fcitx5.android.data.rime

import java.io.File

class RimeCustomPatch(private val begin: String, private val end: String, private val created: String) {

    /** the lines between the markers in [file], or null if it has no block */
    fun read(file: File): String? {
        val text = file.takeIf { it.exists() }?.readText() ?: return null
        if (!text.contains(begin)) return null
        return text.substringAfter(begin).substringBefore(end)
    }

    /** Writes [settings] into every <schema>.custom.yaml; returns the files that couldn't be changed. */
    fun apply(dir: File, schemas: List<String>, settings: List<Pair<String, String>>): List<String> {
        dir.mkdirs()
        val failed = mutableListOf<String>()
        schemas.forEach { id ->
            val file = File(dir, "$id.custom.yaml")
            val old = if (file.exists()) file.readText() else "$created\npatch:\n"
            val new = addBlock(removeBlock(old), settings)
            if (new == null) failed.add(file.name) else file.writeText(new)
        }
        return failed
    }

    /** Removes the block; deletes files the app created that hold nothing else. */
    fun remove(dir: File, schemas: List<String>) {
        schemas.forEach { id ->
            val file = File(dir, "$id.custom.yaml")
            if (!file.exists()) return@forEach
            val text = dropEmptyPatch(removeBlock(file.readText()))
            val rest = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            if (rest.isEmpty() && text.startsWith(CREATED_PREFIX)) file.delete()
            else file.writeText(text)
        }
    }

    /** A `patch:` left without entries would be null, which Rime doesn't take as a patch. */
    private fun dropEmptyPatch(text: String): String {
        val lines = text.lines()
        val patch = lines.indexOfFirst { it.trimEnd() == "patch:" }
        if (patch < 0) return text
        val entries = lines.drop(patch + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }
        if (entries.any { it.isNotBlank() && !it.trimStart().startsWith("#") }) return text
        return (lines.take(patch) + lines.drop(patch + 1 + entries.size)).joinToString("\n")
    }

    private fun removeBlock(text: String): String {
        val lines = text.lines()
        val b = lines.indexOfFirst { it.trim() == begin }
        if (b < 0) return text
        val e = lines.indexOfFirst { it.trim() == end }.takeIf { it > b } ?: return text
        return (lines.take(b) + lines.drop(e + 1)).joinToString("\n")
    }

    /**
     * Puts the settings right after the top-level `patch:` (adding one if missing), indented like
     * the entries already there, and drops the user's own lines for the same keys. Null if the
     * patch is written in a form this doesn't handle (e.g. `patch: { ... }`).
     */
    private fun addBlock(text: String, settings: List<Pair<String, String>>): String? {
        val lines = text.lines().toMutableList()
        var patch = lines.indexOfFirst { it.startsWith("patch:") }
        if (patch < 0) {
            if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
            lines.add("patch:")
            patch = lines.lastIndex
        } else if (lines[patch].removePrefix("patch:").substringBefore('#').isNotBlank()) {
            return null
        }
        // the patch's entries: the indented lines after it
        var e = patch + 1
        while (e < lines.size && (lines[e].isBlank() || lines[e].first().isWhitespace())) e++
        val entries = lines.subList(patch + 1, e)
        val indent = entries.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.takeWhile { it.isWhitespace() } ?: "  "
        val keys = settings.map { it.first.trim('"') }.toSet()
        entries.removeAll { line ->
            line.startsWith(indent) && !line.startsWith("$indent ") &&
                    line.trim().substringBefore(": ").trim('"', '\'', ' ') in keys
        }
        val block = listOf("$indent$begin") + settings.map { (k, v) -> "$indent$k: $v" } + "$indent$end"
        lines.addAll(patch + 1, block)
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    companion object {
        /** how [created] starts for every block, so a file made for one block is recognised by the others */
        const val CREATED_PREFIX = "# 此文件由小企鹅输入法创建"
    }
}
