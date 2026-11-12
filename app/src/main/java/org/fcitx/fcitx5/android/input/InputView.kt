/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
        keyboardPrefs.floatingKeyboardPortrait,
        keyboardPrefs.floatingKeyboardLandscape,
        keyboardPrefs.floatingKeyboardOpacity,
    )

    // mod: portrait & landscape floating keyboard, see FloatingKeyboard
    private val floating = FloatingKeyboard(service, theme) { updateKeyboardSize() }

    private var glassBackdrop: org.fcitx.fcitx5.android.input.glass.GlassBackdrop? = null

    private val floatingBorderDrawable by lazy {
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16f)
            setColor(android.graphics.Color.TRANSPARENT)
            val strokeColor = if (org.fcitx.fcitx5.android.input.glass.Glass.isActive(theme)) {
                if (theme.isDark) 0x44ebf4ff else 0x62ffffff
            } else {
                if (theme.isDark) 0x33ffffff else 0x24000000
            }
            setStroke(dp(1), strokeColor)
        }
    }

    private val glassBackdropWindow =
        org.fcitx.fcitx5.android.input.glass.GlassBackdropWindow(
            service, theme, org.fcitx.fcitx5.android.input.glass.Glass.style(theme)
        )

    fun onWindowShown() = glassBackdropWindow.onWindowShown(keyboardView, floating.enabled, floating.opacity)
    fun onWindowHidden() = glassBackdropWindow.onWindowHidden()

    /** the floating keyboard's bounds in the window (all that takes touches), or null when docked */
    fun floatingKeyboardBounds(out: Rect): Rect? {
        if (!floating.enabled || !keyboardView.isLaidOut) return null
        keyboardView.getLocationInWindow(floatingLocation)
        out.set(
            floatingLocation[0], floatingLocation[1],
            floatingLocation[0] + keyboardView.width, floatingLocation[1] + keyboardView.height
        )
        return out
    }

    private val floatingLocation = intArrayOf(0, 0)

    private var navBarBottomInset = 0

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        val isFloatingWidthReset =
            (key == keyboardPrefs.floatingKeyboardWidth.key && keyboardPrefs.floatingKeyboardWidth.getValue() < 0) ||
            (key == keyboardPrefs.floatingKeyboardPortraitWidth.key && keyboardPrefs.floatingKeyboardPortraitWidth.getValue() < 0)
        if (isFloatingWidthReset) {
            floating.reload()
        }
        if (isFloatingWidthReset || keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
            glassBackdropWindow.syncBounds(keyboardView, floating.enabled, floating.opacity)
        }
    }

    private val scaledKeyboardFrame: ScaledKeyboardFrame
    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        // customBackground is attached by Glass.attachBackdrop below

        // mod: in-keyboard translate card above the toolbar, see TranslatePanel
        val translatePanel = org.fcitx.fcitx5.android.input.translate.TranslatePanel(service, theme)
        org.fcitx.fcitx5.android.input.translate.TranslateDraft.togglePanel = { translatePanel.toggle() }
        org.fcitx.fcitx5.android.input.translate.TranslateDraft.closePanel = { translatePanel.close() }
        // a panel left open by the previous input view must not keep swallowing input
        org.fcitx.fcitx5.android.input.translate.TranslateDraft.stop()

        val keyboardContent = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(translatePanel.view, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                below(translatePanel.view)
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
            add(floating.handle, lParams(matchConstraints, floating.handleHeight) {
                centerHorizontally()
                bottomOfParent()
            })
        }

        scaledKeyboardFrame = ScaledKeyboardFrame(context).apply {
            id = View.generateViewId()
            addView(keyboardContent, android.widget.FrameLayout.LayoutParams(matchParent, wrapContent))
        }
        keyboardView = scaledKeyboardFrame

        add(preedit.ui.root, lParams(matchConstraints, wrapContent) {
            above(keyboardView)
            // mod: follow the keyboard when it floats
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })

        glassBackdrop = org.fcitx.fcitx5.android.input.glass.Glass.attachBackdrop(keyboardView, customBackground, theme, keyBorder)
        updateKeyboardSize()
        windowManager.view.setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (floating.isTranslucent && child != null) {
                    applyFloatingTranslucencyToSubtree(child)
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            glassBackdropWindow.syncBounds(keyboardView, floating.enabled, floating.opacity)
        }
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        if (floating.enabled) {
            updateFloatingKeyboard()
            return
        }
        floating.handle.visibility = GONE
        scaledKeyboardFrame.contentScale = 1f
        keyboardView.apply {
            updateLayoutParams<LayoutParams> {
                width = LayoutParams.MATCH_PARENT
                leftToLeft = unset
                leftMargin = 0
                bottomMargin = 0
                centerHorizontally()
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = false
            elevation = 0f
        }
        popup.root.elevation = 0f
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            height = keyboardBottomPaddingPx
            bottomMargin = navBarBottomInset
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        applyFloatingTranslucency()
    }

    /** mod: a small keyboard with rounded corners where the user left it, the bar at its bottom moves / resizes it */
    private fun updateFloatingKeyboard() {
        floating.fit(width, height)
        floating.handle.visibility = VISIBLE
        scaledKeyboardFrame.contentScale = floating.uiScale
        keyboardView.apply {
            updateLayoutParams<LayoutParams> {
                width = floating.keyboardWidth
                startToStart = unset
                endToEnd = unset
                leftToLeft = LayoutParams.PARENT_ID
                leftMargin = floating.keyboardX
                bottomMargin = floating.keyboardBottom
            }
            outlineProvider = floating.outlineProvider
            clipToOutline = true
            elevation = floating.elevation
            invalidateOutline()
        }
        // key previews must stay above the raised keyboard
        popup.root.elevation = kotlin.math.max(floating.elevation * 2f, dp(8f))
        windowManager.view.updateLayoutParams<LayoutParams> {
            height = floating.unscaledKeysHeight
            startToEnd = unset
            endToStart = unset
            startOfParent()
            endOfParent()
        }
        leftPaddingSpace.visibility = GONE
        rightPaddingSpace.visibility = GONE
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            height = floating.handleHeight
            bottomMargin = 0
        }
        preedit.ui.root.setPadding(dp(8), 0, dp(8), dp(2))
        kawaiiBar.view.setPadding(0, 0, 0, 0)
        applyFloatingTranslucency()
    }

    /**
     * mod: makes floating keyboard background & key bodies optically translucent so underlying
     * app content remains faintly visible while keeping key labels & icons 100% crisp.
     */
    private fun applyFloatingTranslucency() {
        val glass = org.fcitx.fcitx5.android.input.glass.Glass
        val translucent = floating.isTranslucent
        keyboardView.foreground = if (floating.enabled) floatingBorderDrawable else null
        if (!translucent) {
            customBackground.imageAlpha = 255
            glassBackdrop?.floatingSurfaceAlpha = 1f
            if (!glass.isActive(theme)) {
                glass.applyNonGlassFloatingAlpha(keyboardView, 255)
                glass.applyNonGlassFloatingAlpha(preedit.ui.root, 255, skipRoot = false)
            }
            glass.applyFloatingTextContrast(keyboardView, theme, false)
            glass.applyFloatingTextContrast(preedit.ui.root, theme, false)
            glass.invalidateGlassDescendants(this)
            keyboardView.invalidate()
            return
        }

        val op = floating.opacity
        val cardAlpha = (op * op * 0.72f).coerceIn(0.05f, 0.95f)
        customBackground.imageAlpha = (cardAlpha * 255f).toInt().coerceIn(12, 255)
        glassBackdrop?.floatingSurfaceAlpha = (0.10f + op * 0.58f).coerceIn(0.20f, 0.95f)

        if (!glass.isActive(theme)) {
            val nonGlassAlpha = ((0.18f + op * 0.68f) * 255f).toInt().coerceIn(45, 255)
            glass.applyNonGlassFloatingAlpha(keyboardView, nonGlassAlpha)
            glass.applyNonGlassFloatingAlpha(preedit.ui.root, nonGlassAlpha, skipRoot = false)
        }
        glass.applyFloatingTextContrast(keyboardView, theme, true)
        glass.applyFloatingTextContrast(preedit.ui.root, theme, true)
        glass.invalidateGlassDescendants(this)
        keyboardView.invalidate()
    }

    private fun applyFloatingTranslucencyToSubtree(subtree: View) {
        val glass = org.fcitx.fcitx5.android.input.glass.Glass
        if (!glass.isActive(theme)) {
            val op = floating.opacity
            val nonGlassAlpha = ((0.18f + op * 0.68f) * 255f).toInt().coerceIn(45, 255)
            glass.applyNonGlassFloatingAlpha(subtree, nonGlassAlpha, skipRoot = false)
        }
        glass.applyFloatingTextContrast(subtree, theme, true)
        glass.invalidateGlassDescendants(subtree)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // keep the floating keyboard inside the window; not during this layout pass
        if (floating.enabled) post { updateKeyboardSize() }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navBarBottomInset = getNavBarBottomInset(insets)
        if (!floating.enabled) {
            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                bottomMargin = navBarBottomInset
            }
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        glassBackdropWindow.dismiss()
        scope.clear()
        super.onDetachedFromWindow()
    }

    /**
     * Measures its child at unscaled `(width / contentScale)` and scales it uniformly by [contentScale]
     * so toolbar buttons, letters, icons, and key margins shrink proportionally when floating.
     */
    private class ScaledKeyboardFrame(context: android.content.Context) : android.widget.FrameLayout(context) {
        var contentScale: Float = 1f
            set(value) {
                val clamped = value.coerceIn(0.40f, 1f)
                if (field != clamped) {
                    field = clamped
                    getChildAt(0)?.let { child ->
                        child.pivotX = 0f
                        child.pivotY = 0f
                        child.scaleX = clamped
                        child.scaleY = clamped
                    }
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val child = getChildAt(0)
            val s = contentScale
            if (child == null || s >= 0.999f) {
                child?.apply {
                    pivotX = 0f
                    pivotY = 0f
                    scaleX = 1f
                    scaleY = 1f
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val visualW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val innerW = kotlin.math.round(visualW / s).toInt().coerceAtLeast(1)
            val childWSpec = MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY)
            val childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(childWSpec, childHSpec)
            val innerH = child.measuredHeight.coerceAtLeast(1)
            val scaledH = kotlin.math.round(innerH * s).toInt().coerceAtLeast(1)
            val visualH = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                MeasureSpec.AT_MOST -> kotlin.math.min(scaledH, MeasureSpec.getSize(heightMeasureSpec))
                else -> scaledH
            }
            setMeasuredDimension(visualW, visualH)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0) ?: return
            val s = contentScale
            if (s >= 0.999f) {
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = 1f
                child.scaleY = 1f
                super.onLayout(changed, left, top, right, bottom)
                return
            }
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = s
            child.scaleY = s
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

}
