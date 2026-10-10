/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Simple full-screen layout with a Toolbar that handles edge-to-edge insets (Android 15+),
 * used by the common phrase and AI settings screens.
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun AppCompatActivity.setContentWithToolbar(content: View, title: CharSequence) {
    val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    val fg = a.getColorStateList(0) ?: ColorStateList.valueOf(0xff000000.toInt())
    a.recycle()
    val toolbar = Toolbar(this).apply {
        this.title = title
        setTitleTextColor(fg)
        navigationIcon = AppCompatResources.getDrawable(
            context, androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )?.mutate()?.also { DrawableCompat.setTintList(it, fg) }
        overflowIcon?.let { DrawableCompat.setTintList(it.mutate(), fg) }
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    setContentView(root)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    // must be set after setSupportActionBar, which replaces the navigation click listener
    toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
}
