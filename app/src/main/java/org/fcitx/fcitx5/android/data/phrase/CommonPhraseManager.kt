/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Common phrases (常用语): user-defined text snippets with a description, organized in groups.
 */
package org.fcitx.fcitx5.android.data.phrase

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CommonPhrase(
    val id: String = UUID.randomUUID().toString(),
    var label: String,
    var content: String
)

data class PhraseGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val phrases: MutableList<CommonPhrase> = mutableListOf()
)

object CommonPhraseManager {

    private const val FILE_NAME = "common_phrases.json"

    private var groups: MutableList<PhraseGroup>? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun groups(ctx: Context): List<PhraseGroup> {
        groups?.let { return it }
        val loaded = load(ctx)
        groups = loaded
        return loaded
    }

    /** Force re-read from disk (the editor and the keyboard may live in different components). */
    @Synchronized
    fun reload(ctx: Context) {
        groups = load(ctx)
    }

    private fun load(ctx: Context): MutableList<PhraseGroup> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf(defaultGroup())
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val g = arr.getJSONObject(i)
                val ps = g.getJSONArray("phrases")
                PhraseGroup(
                    g.getString("id"), g.getString("name"),
                    MutableList(ps.length()) { j ->
                        val p = ps.getJSONObject(j)
                        CommonPhrase(p.getString("id"), p.getString("label"), p.getString("content"))
                    }
                )
            }.ifEmpty { mutableListOf(defaultGroup()) }
        } catch (e: Exception) {
            mutableListOf(defaultGroup())
        }
    }

    private fun defaultGroup() = PhraseGroup(name = "默认")

    @Synchronized
    fun save(ctx: Context) {
        val gs = groups ?: return
        val arr = JSONArray()
        gs.forEach { g ->
            val ps = JSONArray()
            g.phrases.forEach { p ->
                ps.put(JSONObject().put("id", p.id).put("label", p.label).put("content", p.content))
            }
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("phrases", ps))
        }
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString(2))
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
        listeners.toList().forEach { it() }
    }

    private fun mutable(ctx: Context): MutableList<PhraseGroup> {
        groups(ctx); return groups!!
    }

    fun addGroup(ctx: Context, name: String) { mutable(ctx).add(PhraseGroup(name = name)); save(ctx) }

    fun renameGroup(ctx: Context, groupId: String, name: String) {
        mutable(ctx).find { it.id == groupId }?.name = name; save(ctx)
    }

    fun deleteGroup(ctx: Context, groupId: String) {
        val gs = mutable(ctx)
        gs.removeAll { it.id == groupId }
        if (gs.isEmpty()) gs.add(defaultGroup())
        save(ctx)
    }

    fun moveGroup(ctx: Context, groupId: String, delta: Int) {
        val gs = mutable(ctx)
        val i = gs.indexOfFirst { it.id == groupId }
        val j = i + delta
        if (i < 0 || j !in gs.indices) return
        gs.add(j, gs.removeAt(i)); save(ctx)
    }

    fun addPhrase(ctx: Context, groupId: String, label: String, content: String) {
        mutable(ctx).find { it.id == groupId }?.phrases?.add(CommonPhrase(label = label, content = content))
        save(ctx)
    }

    fun updatePhrase(ctx: Context, phraseId: String, label: String, content: String, newGroupId: String) {
        val gs = mutable(ctx)
        val from = gs.find { g -> g.phrases.any { it.id == phraseId } } ?: return
        val p = from.phrases.first { it.id == phraseId }
        p.label = label; p.content = content
        if (from.id != newGroupId) {
            gs.find { it.id == newGroupId }?.let { to -> from.phrases.remove(p); to.phrases.add(p) }
        }
        save(ctx)
    }

    fun deletePhrase(ctx: Context, phraseId: String) {
        mutable(ctx).forEach { g -> g.phrases.removeAll { it.id == phraseId } }; save(ctx)
    }

    fun movePhrase(ctx: Context, phraseId: String, delta: Int) {
        val g = mutable(ctx).find { g -> g.phrases.any { it.id == phraseId } } ?: return
        val i = g.phrases.indexOfFirst { it.id == phraseId }
        val j = i + delta
        if (j !in g.phrases.indices) return
        g.phrases.add(j, g.phrases.removeAt(i)); save(ctx)
    }
}
