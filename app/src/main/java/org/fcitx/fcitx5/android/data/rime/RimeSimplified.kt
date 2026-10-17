/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 简繁候选统一: 白霜's dictionary has some traditional characters (e.g. 書), so they show up among
 * simplified candidates. This adds an OpenCC t2s filter in front of 白霜's own filters for every
 * 白霜 schema, switched on by a hidden option that is always on:
 *  - 简体 mode: everything is converted to simplified, duplicates removed (uniquifier);
 *  - 繁体 mode: 白霜's traditionalize filter (s2t) runs after it, so everything ends up traditional.
 * Written into <schema>.custom.yaml between markers (RimeCustomPatch), like RimeGrammar.
 */
package org.fcitx.fcitx5.android.data.rime

import android.content.Context
import java.io.File

object RimeSimplified {

    private val PATCH = RimeCustomPatch(
        begin = "# >>> 简繁候选统一（由小企鹅输入法 设置 → Rime 语法模型 添加，可以整段删除）",
        end = "# <<< 简繁候选统一",
        created = "${RimeCustomPatch.CREATED_PREFIX}（简繁候选统一）"
    )

    private val SETTINGS = listOf(
        // a switch without states is hidden from the menus; reset: 1 keeps it on
        "\"switches/@next\"" to "{ name: zh_unify, reset: 1 }",
        "\"engine/filters/@before 0\"" to "simplifier@zh_unify",
        "\"engine/filters/@after 0\"" to "uniquifier",
        "zh_unify/option_name" to "zh_unify",
        "zh_unify/opencc_config" to "t2s.json",
        "zh_unify/tips" to "none",
    )

    fun enabled(ctx: Context) =
        PATCH.read(File(RimeGrammar.rimeDir(ctx), "rime_frost.custom.yaml")) != null

    /** Returns the files that couldn't be changed. */
    fun enable(ctx: Context): List<String> =
        PATCH.apply(RimeGrammar.rimeDir(ctx), RimeGrammar.SCHEMAS, SETTINGS)

    fun disable(ctx: Context) = PATCH.remove(RimeGrammar.rimeDir(ctx), RimeGrammar.SCHEMAS)
}
