/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * AI 联想: predict the next common words / short phrases from the text before the cursor.
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.Context

object AiSuggest {

    const val MAX_CONTEXT = 200

    private const val SYSTEM_PROMPT =
        "你是手机输入法的联想词引擎。你会收到用户光标前已经输入的文字，" +
                "请预测用户紧接着最可能输入的 5 个常用词语或短语。要求：" +
                "1. 只输出接下来的部分，不要重复已有文字；" +
                "2. 每个 1 到 8 个字，以常用词语和简短短语为主，不要长句，不要解释；" +
                "3. 按可能性从高到低排列，5 个之间不要重复；" +
                "4. 语言与原文一致（原文是英文就给英文单词或短语）；" +
                "5. 每行一个，共 5 行，不要编号、引号或标点符号。"

    suspend fun predict(ctx: Context, before: String): List<String> {
        val provider = AiConfig.predictProvider(ctx) ?: AiConfig.provider(ctx)
        // small local models: shorter context is faster; Qwen3 understands /no_think
        val input = if (provider == AiProvider.Local) before.takeLast(80) + " /no_think"
        else before.takeLast(MAX_CONTEXT)
        val reply = AiClient.chat(
            provider, AiConfig.baseUrl(ctx, provider), AiConfig.apiKey(ctx, provider),
            AiConfig.predictModel(ctx, provider), SYSTEM_PROMPT, input,
            maxTokens = 80, temperature = 0.3, fast = true
        )
        return reply.lines()
            .map {
                it.trim()
                    .replace(Regex("^(\\d+[.、)）]|[-•*])\\s*"), "")
                    .trim('"', '“', '”', '「', '」', '\'')
                    .trimEnd('。', '，', '.', ',')
            }
            .filter { it.isNotEmpty() && it.length <= 16 }
            .distinct()
            .take(5)
    }
}
