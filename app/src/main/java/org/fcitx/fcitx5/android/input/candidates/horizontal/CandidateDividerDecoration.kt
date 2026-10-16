/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: dividers between candidates in the scrolling candidate bar (a LinearLayoutManager row);
 * same look as FlexboxVerticalDecoration, which only works with FlexboxLayoutManager.
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp

class CandidateDividerDecoration(private val drawable: Drawable) : RecyclerView.ItemDecoration() {

    private fun ltr(parent: RecyclerView) = parent.layoutDirection != View.LAYOUT_DIRECTION_RTL

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (ltr(parent)) outRect.set(0, 0, drawable.intrinsicWidth, 0)
        else outRect.set(drawable.intrinsicWidth, 0, 0, 0)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val vInset = parent.dp(8)
        for (i in 0 until parent.childCount) {
            val view = parent.getChildAt(i)
            val left = if (ltr(parent)) view.right + view.translationX.toInt()
            else view.left + view.translationX.toInt() - drawable.intrinsicWidth
            drawable.setBounds(left, view.top + vInset, left + drawable.intrinsicWidth, view.bottom - vInset)
            drawable.draw(c)
        }
    }
}
