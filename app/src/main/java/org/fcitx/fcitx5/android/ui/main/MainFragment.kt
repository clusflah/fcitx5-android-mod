/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import splitties.resources.styledColor

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            // mod: search all settings
            menu.item(
                R.string.settings_search, R.drawable.ic_baseline_search_24,
                requireContext().styledColor(android.R.attr.colorControlNormal), true
            ) {
                startActivity(Intent(requireContext(), SettingsSearchActivity::class.java))
            }
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            }
            menu.item(R.string.developer) {
                navigateWithAnim(SettingsRoute.Developer)
            }
            menu.item(R.string.about) {
                navigateWithAnim(SettingsRoute.About)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // mod: grouped by what people want to do, instead of "Fcitx" / "Android"
            addCategory(R.string.settings_group_typing) {
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow
                )
                addDestinationPreference(
                    R.string.emoji_and_symbols,
                    R.drawable.ic_baseline_emoji_symbols_24,
                    SettingsRoute.Symbol
                )
                addPreference(R.string.rime_grammar, icon = R.drawable.ic_baseline_auto_awesome_24) {
                    startActivity(Intent(requireContext(), RimeGrammarActivity::class.java))
                }
            }
            addCategory(R.string.settings_group_voice_ai) {
                addPreference(R.string.offline_voice, R.string.settings_voice_summary, R.drawable.ic_baseline_keyboard_voice_24) {
                    startActivity(Intent(requireContext(), OfflineVoiceActivity::class.java))
                }
                addPreference(R.string.ai_assist, R.string.settings_ai_summary, R.drawable.ic_ai_assist) {
                    startActivity(Intent(requireContext(), AiSettingsActivity::class.java))
                }
            }
            addCategory(R.string.settings_group_clipboard) {
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard
                )
                addPreference(R.string.common_phrase, icon = R.drawable.ic_common_phrase) {
                    startActivity(Intent(requireContext(), CommonPhraseActivity::class.java))
                }
            }
            addCategory(R.string.settings_group_look) {
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
            }
            addCategory(R.string.settings_group_more) {
                addPreference(R.string.mod_welcome, icon = R.drawable.ic_baseline_info_24) {
                    startActivity(Intent(requireContext(), ModWelcomeActivity::class.java))
                }
                addPreference(R.string.setup_check, icon = R.drawable.ic_baseline_settings_24) {
                    startActivity(Intent(requireContext(), SetupCheckActivity::class.java))
                }
                addDestinationPreference(
                    R.string.plugins,
                    R.drawable.ic_baseline_android_24,
                    SettingsRoute.Plugin
                )
                addDestinationPreference(
                    R.string.global_options,
                    R.drawable.ic_baseline_tune_24,
                    SettingsRoute.GlobalConfig
                )
                addDestinationPreference(
                    R.string.addons,
                    R.drawable.ic_baseline_extension_24,
                    SettingsRoute.AddonList
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
            }
        }
    }
}