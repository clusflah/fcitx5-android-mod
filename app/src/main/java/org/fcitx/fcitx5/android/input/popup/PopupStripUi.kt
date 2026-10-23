/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Long-press popup of the nine-key keys: one row of characters over the row above the key
 * ("a b c A B C" for ABC), with [StripPopup.focus] picked at first. Sliding left or right moves
 * the pick one cell per cell width, wherever the finger goes up or down; letting go types it as is.
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.gravityCenter
import kotlin.math.floor

/** [items] committed as text rather than sent to the input method; [focus] is picked first. */
class StripPopup(items: Array<String>, val focus: Int) : KeyDef.Popup.Keyboard.Explicit(items)

class PopupStripUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    radius: Float,
    /** gap above and below each key inside its row */
    verticalMargin: Int,
    private val items: Array<String>,
    focus: Int
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    private val cellWidth = ctx.dp(34)
    private val height = triggerBounds.height() - verticalMargin * 2
    private val initialFocus = focus.coerceIn(0, items.lastIndex)

    // the picked cell sits over the key's center, the whole strip kept on screen
    override val offsetX = (triggerBounds.centerX() - cellWidth * initialFocus - cellWidth / 2)
        .coerceAtMost(outerBounds.right - cellWidth * items.size)
        .coerceAtLeast(outerBounds.left) - triggerBounds.left

    // in the row above, with the same gaps as the keys
    override val offsetY = verticalMargin - triggerBounds.height()

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.genericActiveBackgroundColor)
    }

    private val cells = items.map {
        ctx.textView {
            text = it
            textSize = 20f
            gravity = gravityCenter
            setTextColor(theme.popupTextColor)
        }
    }

    override val root = ctx.horizontalLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.popupBackgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        cells.forEach { add(ctx.frameLayout { add(it, lParams(cellWidth, height)) }, lParams()) }
    }

    private var focused = -1

    /** where the finger was when the strip first heard of it; the pick moves relative to it */
    private var anchorX = Float.NaN

    init {
        markFocus(initialFocus)
    }

    private fun markFocus(index: Int) {
        if (index == focused) return
        cells.getOrNull(focused)?.apply {
            background = null
            setTextColor(theme.popupTextColor)
        }
        cells[index].apply {
            background = focusBackground
            setTextColor(theme.genericActiveForegroundColor)
        }
        focused = index
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        if (anchorX.isNaN()) anchorX = x
        val moved = floor((x - anchorX) / cellWidth + 0.5f).toInt()
        markFocus(limitIndex(initialFocus + moved, items.size))
        return false
    }

    override fun onTrigger(): KeyAction? =
        items.getOrNull(focused)?.let { KeyAction.CommitAction(it) }
}
