/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Management screen for common phrases (常用语) and their groups.
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.phrase.CommonPhrase
import org.fcitx.fcitx5.android.data.phrase.CommonPhraseManager
import org.fcitx.fcitx5.android.data.phrase.PhraseGroup
import splitties.dimensions.dp

class CommonPhraseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP = "group_id"
    }

    private var groupId: String? = null
    private lateinit var tabs: LinearLayout
    private lateinit var list: ListView
    private lateinit var empty: TextView

    private val groups get() = CommonPhraseManager.groups(this)
    private val currentGroup get() = groups.find { it.id == groupId } ?: groups.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonPhraseManager.reload(this)
        groupId = intent.getStringExtra(EXTRA_GROUP)
            ?: groups.firstOrNull { it.phrases.isNotEmpty() }?.id

        val pad = dp(12)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabs = LinearLayout(this).apply { setPadding(pad, pad, pad, 0) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(tabs)
        })
        list = ListView(this)
        empty = TextView(this).apply {
            text = getString(R.string.common_phrase_empty_manage)
            gravity = Gravity.CENTER; setPadding(pad, pad * 3, pad, pad)
        }
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = getString(R.string.common_phrase_add)
            setOnClickListener { editPhrase(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(pad, pad / 2, pad, pad)
        })
        setContentWithToolbar(root, getString(R.string.common_phrase))
        list.setOnItemClickListener { _, _, pos, _ -> editPhrase(currentGroup.phrases[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> phraseMenu(currentGroup.phrases[pos]); true }
        refresh()
    }

    private fun refresh() {
        val g = currentGroup
        groupId = g.id
        tabs.removeAllViews()
        groups.forEach { grp ->
            tabs.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = grp.name
                isAllCaps = false
                alpha = if (grp.id == g.id) 1f else 0.55f
                setOnClickListener { groupId = grp.id; refresh() }
                setOnLongClickListener { groupId = grp.id; refresh(); groupMenu(); true }
            })
        }
        val rows = g.phrases.map { if (it.label.isBlank()) it.content else "${it.label}：${it.content}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        empty.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun input(hint: String, value: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
    }

    private fun editPhrase(p: CommonPhrase?) {
        val label = input(getString(R.string.common_phrase_label_hint), p?.label ?: "")
        val content = input(getString(R.string.common_phrase_content_hint), p?.content ?: "", true)
        val gs = groups
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, gs.map { it.name })
            setSelection(gs.indexOfFirst { it.id == currentGroup.id }.coerceAtLeast(0))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label); addView(content); addView(spinner)
        }
        AlertDialog.Builder(this)
            .setTitle(if (p == null) R.string.common_phrase_add else R.string.common_phrase_edit)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val c = content.text.toString()
                if (c.isEmpty()) {
                    Toast.makeText(this, R.string.common_phrase_content_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = gs[spinner.selectedItemPosition].id
                if (p == null) CommonPhraseManager.addPhrase(this, target, label.text.toString().trim(), c)
                else CommonPhraseManager.updatePhrase(this, p.id, label.text.toString().trim(), c, target)
                groupId = target
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun phraseMenu(p: CommonPhrase) {
        val items = arrayOf(
            getString(R.string.common_phrase_edit), getString(R.string.move_up),
            getString(R.string.move_down), getString(R.string.delete)
        )
        AlertDialog.Builder(this).setTitle(p.label.ifBlank { p.content }).setItems(items) { _, which ->
            when (which) {
                0 -> editPhrase(p)
                1 -> CommonPhraseManager.movePhrase(this, p.id, -1)
                2 -> CommonPhraseManager.movePhrase(this, p.id, 1)
                3 -> CommonPhraseManager.deletePhrase(this, p.id)
            }
            refresh()
        }.show()
    }

    private fun nameDialog(title: Int, initial: String, onOk: (String) -> Unit) {
        val et = input(getString(R.string.common_phrase_group_name), initial)
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun groupMenu() {
        val g: PhraseGroup = currentGroup
        val items = arrayOf(
            getString(R.string.common_phrase_group_rename), getString(R.string.move_up),
            getString(R.string.move_down), getString(R.string.common_phrase_group_delete)
        )
        AlertDialog.Builder(this).setTitle(g.name).setItems(items) { _, which ->
            when (which) {
                0 -> nameDialog(R.string.common_phrase_group_rename, g.name) {
                    CommonPhraseManager.renameGroup(this, g.id, it)
                }
                1 -> CommonPhraseManager.moveGroup(this, g.id, -1)
                2 -> CommonPhraseManager.moveGroup(this, g.id, 1)
                3 -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.common_phrase_group_delete_confirm, g.name, g.phrases.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        CommonPhraseManager.deleteGroup(this, g.id); groupId = null; refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            refresh()
        }.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.common_phrase_group_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, R.string.common_phrase_group_manage)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> nameDialog(R.string.common_phrase_group_add, "") {
                CommonPhraseManager.addGroup(this, it); groupId = groups.last().id
            }
            2 -> groupMenu()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
