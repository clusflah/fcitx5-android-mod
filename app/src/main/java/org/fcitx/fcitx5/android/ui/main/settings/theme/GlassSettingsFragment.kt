/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: settings tab for Liquid Glass in ThemeFragment, with live preview at the top.
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.theme.ThemeManager

class GlassSettingsFragment : ManagedPreferenceFragment(ThemeManager.glassPrefs)
