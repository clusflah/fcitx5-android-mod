/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Runs a custom prompt on a text with the default model; shared by the assistant dialog and the
 * keyboard's long-press on the 大模型 button (replace the field's text with the reply).
 */
package org.fcitx.fcitx5.android.data.ai

import android.content.Context

object AiPromptRunner {

    /** (system, user): a prompt with {{input}} becomes the user message, otherwise the system instruction */
    fun messages(prompt: String, text: String): Pair<String, String> =
        if (prompt.contains("{{input}}")) "" to prompt.replace("{{input}}", text)
        else prompt to text

    /** The reply in the output format chosen in the dialog (plain text by default). */
    suspend fun run(ctx: Context, prompt: AiPrompt, text: String): CharSequence {
        val provider = AiConfig.provider(ctx)
        val (system, user) = messages(prompt.content, text)
        val reply = AiClient.chat(
            provider, AiConfig.baseUrl(ctx, provider), AiConfig.apiKey(ctx, provider),
            AiConfig.model(ctx, provider), system, user
        )
        return when (AiConfig.outputFormat(ctx)) {
            1 -> MarkdownText.render(reply)
            2 -> reply
            else -> MarkdownText.toPlain(reply)
        }
    }
}
