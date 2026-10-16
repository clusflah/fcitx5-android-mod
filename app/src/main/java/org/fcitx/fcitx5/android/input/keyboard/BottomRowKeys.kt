/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Bottom-row keys shared by the 26-key layout and the 九宫格, laid out like the common Chinese
 * keyboards: "，。" left of space and a 中/英 key right of it, in the same place on both.
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import splitties.dimensions.dp

/**
 * Tap: next input method; long press: input method picker. Shows "中" and "英" with the current
 * one bright and the other faded (see [showLanguage]). Uses the 🌐 key's id, so the 26-key layout's
 * "show language switch key" setting still hides it.
 */
class ZhEnKey(percentWidth: Float = 0.1f, variant: Variant = Variant.Normal) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_language_24,
        percentWidth = percentWidth,
        variant = variant,
        viewId = R.id.button_lang
    ),
    setOf(
        Behavior.Press(KeyAction.LangSwitchAction),
        Behavior.LongPress(KeyAction.ShowInputMethodPickerAction)
    )
)

/** keyboard layouts (keyboard-us …) count as 英, every input method engine as 中 */
fun InputMethodEntry.isLatinLayout() = uniqueName.startsWith("keyboard-")

fun ImageKeyView.showLanguage(ime: InputMethodEntry) {
    val color = img.imageTintList?.defaultColor ?: 0xff000000.toInt()
    img.setImageDrawable(ZhEnDrawable(context, chinese = !ime.isLatinLayout(), color))
}

/**
 * Tap ",", swipe "." (both converted by the input method, e.g. to ，。), long press for more.
 * The labels follow the punctuation conversion (TextKeyboard.updatePunctuationKeys).
 */
class CommaPeriodKey(percentWidth: Float = 0.1f, variant: Variant = Variant.Normal) : KeyDef(
    Appearance.AltText(
        displayText = ",",
        altText = ".",
        textSize = 23f,
        percentWidth = percentWidth,
        variant = variant
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(",")),
        Behavior.Swipe(KeyAction.FcitxKeyAction("."))
    ),
    arrayOf(
        Popup.AltPreview(",", "."),
        Popup.Keyboard.Preset(PRESET)
    )
) {
    companion object {
        /** PopupPreset entry with the long-press symbols */
        const val PRESET = ",."
    }
}

/** "中" top-left and "英" bottom-right with two corner arcs; the inactive one is faded. */
private class ZhEnDrawable(context: Context, private val chinese: Boolean, tint: Int) : Drawable() {

    private val size = context.dp(28)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
        alpha = FADED
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val left = b.exactCenterX() - s / 2
        val top = b.exactCenterY() - s / 2
        paint.textSize = s * 0.5f
        val baseline = -(paint.ascent() + paint.descent()) / 2
        paint.alpha = if (chinese) FULL else FADED
        canvas.drawText("中", left + s * 0.3f, top + s * 0.3f + baseline, paint)
        paint.alpha = if (chinese) FADED else FULL
        canvas.drawText("英", left + s * 0.7f, top + s * 0.7f + baseline, paint)
        // ⤵ top-right and ⤴ bottom-left, hinting that the key switches between the two
        val r = s * 0.18f
        canvas.drawArc(RectF(left + s - 2 * r, top + s * 0.04f, left + s, top + s * 0.04f + 2 * r), 270f, 90f, false, arc)
        canvas.drawArc(RectF(left, top + s * 0.96f - 2 * r, left + 2 * r, top + s * 0.96f), 90f, 90f, false, arc)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        arc.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val FULL = 0xff
        private const val FADED = 0x55
    }
}
