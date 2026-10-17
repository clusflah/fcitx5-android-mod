/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: the expanded candidate list of the 九宫格, laid out like the common Chinese nine-key keypads:
 * "词频 / 单字" tabs and ∧ in the toolbar, the pinyin to choose from on the left, the candidates
 * five to a row from the first one, and ⌫ in the bottom right corner.
 * Opened by the toolbar's ∨ instead of Grid/FlexboxExpandedCandidateWindow while T9Pinyin.active.
 * Candidates are read from fcitx page by page; "单字" keeps only single characters, remembering
 * each one's index in fcitx's list so selecting it picks the right candidate.
 */
package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.T9Pinyin
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class T9CandidateWindow : InputWindow.ExtendedInputWindow<T9CandidateWindow>(), InputBroadcastReceiver {

    private val theme by manager.theme()
    private val fcitx by manager.fcitx()
    private val inputView by manager.inputView()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val bar: KawaiiBarComponent by manager.must()

    override val showTitle = false

    // ---------- candidates ----------

    /** "单字": only single characters */
    private var singleChars = false

    /** (index in fcitx's candidate list, candidate) */
    private val items = mutableListOf<Pair<Int, CandidateWord>>()
    private var nextIndex = 0
    private var exhausted = false
    private var loading = false

    /** bumped on every reload, so pages of an older list are dropped */
    private var generation = 0

    private val adapter = object : RecyclerView.Adapter<CandidateViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CandidateViewHolder(CandidateItemUi(parent.context, theme)).apply {
                itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.dp(ROW_DP))
            }

        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            val (index, word) = items[position]
            holder.update(index, word)
            holder.itemView.setOnClickListener { fcitx.launchOnReady { it.select(holder.idx) } }
            holder.itemView.setOnLongClickListener {
                inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                true
            }
        }

        override fun onViewRecycled(holder: CandidateViewHolder) {
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            holder.clear()
        }
    }

    private lateinit var grid: RecyclerView

    private fun reload() {
        generation++
        items.clear()
        nextIndex = 0
        exhausted = false
        loading = false
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        loadMore()
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val gen = generation
        val start = nextIndex
        fcitx.launchOnReady { api ->
            val page = runCatching { api.getCandidates(start, PAGE) }.getOrNull() ?: emptyArray()
            grid.post {
                if (gen != generation) return@post
                loading = false
                if (page.size < PAGE) exhausted = true
                nextIndex = start + page.size
                val add = page.withIndex()
                    .map { (i, word) -> start + i to word }
                    .filter { (_, word) -> !singleChars || word.text.codePointCount(0, word.text.length) == 1 }
                if (add.isNotEmpty()) {
                    val from = items.size
                    items.addAll(add)
                    adapter.notifyItemRangeInserted(from, add.size)
                }
                // "单字" may filter out a whole page: keep reading until the grid has something to scroll
                if (items.size < MIN_ITEMS) loadMore()
            }
        }
    }

    // ---------- pinyin column ----------

    private var panelPreedit = ""
    private var clientPreedit = ""

    /** Rime input not converted yet */
    private var pendingRaw: String? = null

    private val columnList by lazy { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }

    private fun sendKey(action: KeyAction) =
        commonKeyActionListener.listener.onKeyAction(action, KeyActionListener.Source.Keyboard)

    private fun updatePinyinColumn() {
        val raw = T9Pinyin.pendingInput(panelPreedit.ifEmpty { clientPreedit })
        if (raw == pendingRaw && columnList.childCount > 0) return
        pendingRaw = raw
        columnList.removeAllViews()
        raw?.let { T9Pinyin.options(it) }.orEmpty().forEach { syllable ->
            columnList.addView(TextView(context).apply {
                text = syllable
                textSize = 18f
                gravity = Gravity.CENTER
                minHeight = dp(ROW_DP)
                setTextColor(theme.altKeyTextColor)
                background = pressHighlightDrawable(theme.keyPressHighlightColor)
                setOnClickListener {
                    InputFeedbacks.hapticFeedback(it)
                    choose(syllable)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** same as T9Keyboard: replace the digits the syllable covers with its letters */
    private fun choose(syllable: String) {
        val raw = pendingRaw ?: return
        val (delete, type) = T9Pinyin.replacement(raw, syllable) ?: return
        repeat(delete) { sendKey(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))) }
        type.forEach { sendKey(KeyAction.FcitxKeyAction(it.toString())) }
    }

    // ---------- toolbar: 词频 / 单字 and ∧ ----------

    private fun tab(label: String, single: Boolean) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener {
            if (singleChars == single) return@setOnClickListener
            singleChars = single
            styleTabs()
            reload()
        }
    }

    // views need the context, which the window only gets once it is set up in the scope
    private val freqTab by lazy { tab("词频", false) }
    private val singleTab by lazy { tab("单字", true) }

    private fun styleTabs() {
        listOf(freqTab to !singleChars, singleTab to singleChars).forEach { (view, selected) ->
            view.setTextColor(if (selected) theme.keyTextColor else theme.altKeyTextColor)
            view.background = if (selected) GradientDrawable().apply {
                cornerRadius = context.dp(8f)
                setColor(theme.keyBackgroundColor)
            } else null
        }
    }

    override fun onCreateBarExtension(): View = FrameLayout(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = context.dp(10f)
                setColor(theme.altKeyBackgroundColor)
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(freqTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(singleTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34), Gravity.CENTER))
        addView(ToolButton(context, R.drawable.ic_baseline_expand_less_24, theme).apply {
            contentDescription = context.getString(R.string.hide_candidates_list)
            setOnClickListener { windowManager.attachWindow(KeyboardWindow) }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        styleTabs()
    }

    // ---------- layout ----------

    override fun onCreateView(): View {
        grid = RecyclerView(context).apply {
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@T9CandidateWindow.adapter
            // the last row can scroll out from under ⌫
            clipToPadding = false
            setPadding(0, 0, 0, dp(ROW_DP))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val last = (recyclerView.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                    if (last >= items.size - PRELOAD) loadMore()
                }
            })
        }
        val backspace = ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_backspace_24)
            imageTintList = android.content.res.ColorStateList.valueOf(theme.altKeyTextColor)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(theme.altKeyBackgroundColor)
            foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
            setOnClickListener {
                InputFeedbacks.hapticFeedback(it)
                sendKey(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)))
            }
        }
        val right = ConstraintLayout(context).apply {
            addView(grid, ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            })
            addView(backspace, ConstraintLayout.LayoutParams(0, dp(ROW_DP)).apply {
                matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                matchConstraintPercentWidth = 1f / COLUMNS
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            })
        }
        val column = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(theme.altKeyBackgroundColor)
            addView(columnList)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.keyboardColor)
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.15f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f))
        }
    }

    override fun onAttached() {
        bar.expandButtonStateMachine.push(ExpandedCandidatesAttached)
        fcitx.runImmediately { inputPanelCached }.let { panelPreedit = it.preedit.toString() }
        clientPreedit = fcitx.runImmediately { clientPreeditCached }.toString()
        updatePinyinColumn()
        reload()
    }

    override fun onDetached() {
        generation++
        bar.expandButtonStateMachine.push(ExpandedCandidatesDetached, ExpandedCandidatesEmpty to false)
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (::grid.isInitialized) reload()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        panelPreedit = data.preedit.toString()
        updatePinyinColumn()
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        clientPreedit = data.toString()
        updatePinyinColumn()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) windowManager.attachWindow(KeyboardWindow)
    }

    companion object {
        private const val COLUMNS = 5
        private const val ROW_DP = 52
        private const val PAGE = 60
        private const val PRELOAD = 15
        private const val MIN_ITEMS = 30
    }
}
