/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Keyboard panel showing common phrases (常用语), opened from the toolbar.
 */
package org.fcitx.fcitx5.android.input.phrase

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.phrase.CommonPhrase
import org.fcitx.fcitx5.android.data.phrase.CommonPhraseManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.ui.main.CommonPhraseActivity
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class CommonPhraseWindow : InputWindow.ExtendedInputWindow<CommonPhraseWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val windowManager: org.fcitx.fcitx5.android.input.wm.InputWindowManager by manager.must()

    override val title: String by lazy { context.getString(R.string.common_phrase) }

    private var currentGroupId: String? = null

    private lateinit var tabBar: LinearLayout
    private lateinit var emptyHint: TextView
    private val adapter = PhraseAdapter()

    private val onDataChanged: () -> Unit = { refresh() }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(8f)
    }

    override fun onCreateView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val content = android.widget.FrameLayout(context)
        content.addView(RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@CommonPhraseWindow.adapter
            setPadding(context.dp(4), 0, context.dp(4), context.dp(4))
            clipToPadding = false
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyHint = TextView(context).apply {
            text = context.getString(R.string.common_phrase_empty)
            setTextColor(theme.altKeyTextColor)
            gravity = Gravity.CENTER
            setOnClickListener { openManager() }
        }
        content.addView(emptyHint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onCreateBarExtension(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // 切换回剪贴板
        addView(ToolButton(context, R.drawable.ic_clipboard, theme).apply {
            contentDescription = context.getString(R.string.clipboard)
            setOnClickListener {
                windowManager.attachWindow(org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow())
            }
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        addView(ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.common_phrase_manage)
            setOnClickListener { openManager() }
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
    }

    private fun openManager() {
        service.startActivity(Intent(context, CommonPhraseActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            currentGroupId?.let { putExtra(CommonPhraseActivity.EXTRA_GROUP, it) }
        })
    }

    override fun onAttached() {
        CommonPhraseManager.reload(context)
        CommonPhraseManager.addListener(onDataChanged)
        refresh()
    }

    override fun onDetached() {
        CommonPhraseManager.removeListener(onDataChanged)
    }

    private fun refresh() {
        val groups = CommonPhraseManager.groups(context)
        if (groups.none { it.id == currentGroupId }) currentGroupId = groups.firstOrNull()?.id
        tabBar.removeAllViews()
        groups.forEach { g ->
            val selected = g.id == currentGroupId
            tabBar.addView(TextView(context).apply {
                text = g.name
                textSize = 14f
                setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
                setTextColor(if (selected) theme.accentKeyTextColor else theme.keyTextColor)
                background = rounded(if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
                setOnClickListener { currentGroupId = g.id; refresh() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(6)
            })
        }
        val items = groups.find { it.id == currentGroupId }?.phrases.orEmpty()
        adapter.submit(items)
        emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class PhraseAdapter : RecyclerView.Adapter<PhraseAdapter.VH>() {
        private var items: List<CommonPhrase> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<CommonPhrase>) { items = list.toList(); notifyDataSetChanged() }

        inner class VH(val root: LinearLayout, val label: TextView, val content: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val label = TextView(ctx).apply {
                textSize = 12f; setTextColor(theme.altKeyTextColor)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val content = TextView(ctx).apply {
                textSize = 15f; setTextColor(theme.keyTextColor); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
                background = rounded(theme.clipboardEntryColor)
                addView(label); addView(content)
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                }
            }
            return VH(root, label, content)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.label.text = p.label
            holder.label.visibility = if (p.label.isEmpty()) View.GONE else View.VISIBLE
            holder.content.text = p.content
            holder.root.setOnClickListener { service.commitText(p.content) }
            holder.root.setOnLongClickListener { openManager(); true }
        }

        override fun getItemCount() = items.size
    }
}
