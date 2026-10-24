/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: "编辑工具栏" panel, opened from the 更多 panel. The bar shows a green ✓ (done) and the
 * toolbar's buttons as they are now, each with a − badge that takes it off the toolbar; long press
 * one and slide to move it. Below, a
 * card lists every button as a round tile, five by two per page (swipe for more): the ones on the
 * toolbar are green, tapping another puts it at the end of the toolbar, tapping a green one takes it
 * off. Changes apply at once (the toolbar listens to the same settings as 设置 → 工具栏按钮).
 * 更多 itself isn't listed, so it can't be removed from here.
 */
package org.fcitx.fcitx5.android.input.status

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class ToolbarEditWindow : InputWindow.ExtendedInputWindow<ToolbarEditWindow>() {

    private val theme: Theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val prefs = AppPrefs.getInstance().keyboard

    override val showTitle = false

    /** every button but 更多, in the toolbar's order */
    private fun buttons() =
        ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue()).filter { it != ToolbarButton.More }

    private fun onBar(b: ToolbarButton) = b.show(prefs).getValue()

    private fun label(b: ToolbarButton) = when (b) {
        ToolbarButton.PasswordManager -> "Bitwarden"
        ToolbarButton.Voice -> "语音输入"
        else -> context.getString(b.label)
    }

    private fun setOnBar(b: ToolbarButton, on: Boolean) {
        if (on) {
            // a button put back goes to the end of the toolbar
            val order = ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue()).filter { it != b } + b
            prefs.toolbarButtonOrder.setValue(ToolbarButton.encode(order))
        }
        b.show(prefs).setValue(on)
        refresh()
    }

    // ---- bar: ✓ and the buttons on the toolbar, each with a − badge ----

    private val barRow by lazy { LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    } }

    private val barExtension by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(doneButton(), LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginStart = context.dp(4) })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(barRow)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = context.dp(6) })
        }
    }

    override fun onCreateBarExtension() = barExtension

    private fun doneButton() = ImageView(context).apply {
        setImageResource(R.drawable.ic_baseline_check_24)
        imageTintList = ColorStateList.valueOf(theme.genericActiveForegroundColor)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.genericActiveBackgroundColor)
        }
        foreground = circlePressHighlightDrawable(theme.keyPressHighlightColor)
        contentDescription = context.getString(R.string.done)
        setOnClickListener { windowManager.attachWindow(KeyboardWindow) }
    }

    /** the bar button being dragged to a new place, after a long press */
    private var dragged: View? = null

    /** finger x (raw) the dragged button's resting place lines up with */
    private var dragOriginX = 0f
    private var lastRawX = 0f

    private fun startDrag(view: View) {
        dragged = view
        dragOriginX = lastRawX
        // keep the scroll view from taking the moves
        barRow.parent?.requestDisallowInterceptTouchEvent(true)
        view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).start()
        view.elevation = context.dp(4f)
    }

    /** follow the finger; past half a neighbor, the neighbor moves to the other side */
    private fun drag(rawX: Float) {
        val view = dragged ?: return
        var dx = rawX - dragOriginX
        val w = view.width.takeIf { it > 0 } ?: return
        val i = barRow.indexOfChild(view)
        if (dx > w / 2 && i < barRow.childCount - 1) {
            val next = barRow.getChildAt(i + 1)
            barRow.removeView(next)
            barRow.addView(next, i)
            dragOriginX += w
            dx -= w
        } else if (dx < -w / 2 && i > 0) {
            val prev = barRow.getChildAt(i - 1)
            barRow.removeView(prev)
            barRow.addView(prev, i)
            dragOriginX -= w
            dx += w
        }
        view.translationX = dx
    }

    /** save the new order: the toolbar's buttons as now laid out, then the others as they were */
    private fun endDrag() {
        val view = dragged ?: return
        dragged = null
        view.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(80).start()
        view.elevation = 0f
        val shown = (0 until barRow.childCount).mapNotNull { barRow.getChildAt(it).tag as? ToolbarButton }
        val order = shown + ToolbarButton.ordered(prefs.toolbarButtonOrder.getValue()).filter { it !in shown }
        prefs.toolbarButtonOrder.setValue(ToolbarButton.encode(order))
        refresh()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun barButton(b: ToolbarButton) = FrameLayout(context).apply {
        tag = b
        val frame = this
        addView(ToolButton(context, b.icon, theme).apply {
            contentDescription = label(b)
            setOnClickListener {
                InputFeedbacks.hapticFeedback(it)
                setOnBar(b, false)
            }
            // long press, then slide left / right to move it
            setOnLongClickListener {
                startDrag(frame)
                true
            }
            setOnTouchListener { _, e ->
                lastRawX = e.rawX
                if (dragged !== frame) return@setOnTouchListener false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> drag(e.rawX)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        endDrag()
                        // let the button reset its pressed state; no click follows a long press
                        return@setOnTouchListener false
                    }
                }
                true
            }
        },FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.BOTTOM or Gravity.START))
        // − badge at the top right
        addView(TextView(context).apply {
            text = "−"
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(theme.keyBackgroundColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.altKeyTextColor)
            }
            isClickable = false
        }, FrameLayout.LayoutParams(context.dp(14), context.dp(14), Gravity.TOP or Gravity.END))
    }

    // ---- card: every button as a tile, paged ----

    private val pages by lazy { RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(this)
        overScrollMode = View.OVER_SCROLL_NEVER
    } }

    private val dots by lazy { LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    } }

    private var page = 0

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var chunks: List<List<ToolbarButton>> = emptyList()

        override fun getItemCount() = chunks.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(parent.width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val pageView = holder.itemView as LinearLayout
            pageView.layoutParams.width = pages.width
            pageView.removeAllViews()
            chunks[position].chunked(COLUMNS).forEach { row ->
                pageView.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { addView(tile(it), LinearLayout.LayoutParams(0, context.dp(94), 1f)) }
                    repeat(COLUMNS - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
                })
            }
            // keep pages the same height
            repeat(ROWS - (chunks[position].size + COLUMNS - 1) / COLUMNS) {
                pageView.addView(View(context), LinearLayout.LayoutParams(0, context.dp(94)))
            }
        }
    }

    private fun tile(b: ToolbarButton): View {
        val on = onBar(b)
        val green = theme.genericActiveBackgroundColor
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageResource(b.icon)
                imageTintList = ColorStateList.valueOf(if (on) green else theme.keyTextColor)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(13), context.dp(13), context.dp(13), context.dp(13))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) blend(theme.keyBackgroundColor, green, 0.16f) else theme.keyBackgroundColor)
                }
                foreground = circlePressHighlightDrawable(theme.keyPressHighlightColor)
            }, LinearLayout.LayoutParams(context.dp(50), context.dp(50)))
            addView(TextView(context).apply {
                text = label(b)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(theme.altKeyTextColor)
                setPadding(context.dp(2), context.dp(8), context.dp(2), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                InputFeedbacks.hapticFeedback(it)
                setOnBar(b, !on)
            }
        }
    }

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        fun ch(shift: Int) = ((base shr shift and 0xff) * (1 - amount) + (tint shr shift and 0xff) * amount).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun showDots(count: Int) {
        dots.removeAllViews()
        if (count < 2) return
        repeat(count) { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == page) theme.altKeyTextColor else theme.dividerColor)
                }
            }, LinearLayout.LayoutParams(context.dp(7), context.dp(7)).apply { setMargins(context.dp(4), 0, context.dp(4), 0) })
        }
    }

    private fun refresh() {
        barRow.removeAllViews()
        buttons().filter { onBar(it) }.forEach {
            barRow.addView(barButton(it), LinearLayout.LayoutParams(context.dp(44), context.dp(40)))
        }
        adapter.chunks = buttons().chunked(COLUMNS * ROWS)
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        page = page.coerceAtMost(adapter.chunks.lastIndex.coerceAtLeast(0))
        showDots(adapter.chunks.size)
    }

    override fun onCreateView(): View {
        pages.adapter = adapter
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val p = (pages.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                if (p >= 0 && p != page) {
                    page = p
                    showDots(adapter.chunks.size)
                }
            }
        })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = context.dp(16f)
                setColor(theme.popupBackgroundColor)
            }
            setPadding(0, context.dp(8), 0, context.dp(10))
            addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(12)).apply { topMargin = context.dp(4) })
        }
        return FrameLayout(context).apply {
            setBackgroundColor(theme.keyboardColor)
            val m = context.dp(12)
            setPadding(m, m, m, m)
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onAttached() {
        // the page width is known once laid out
        pages.post { refresh() }
    }

    override fun onDetached() {}

    companion object {
        private const val COLUMNS = 5
        private const val ROWS = 2
    }
}
