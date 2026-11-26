package org.stypox.dicio.skills.fallback.local_ai

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.io.local_ai.LocalAiClient
import org.stypox.dicio.io.local_ai.LocalAiProtocol.Message
import org.stypox.dicio.util.RecognizeEverythingSkill
import java.util.Locale

class LocalAiFallbackSkill(
    correspondingSkillInfo: SkillInfo,
    private val client: LocalAiClient,
) : RecognizeEverythingSkill(correspondingSkillInfo) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        // a follow-up question continues the conversation, if the last answer came recently
        val previous = (ctx.previousOutput as? LocalAiOutput)
            ?.takeIf { System.currentTimeMillis() - it.createdAt < CONVERSATION_TIMEOUT_MILLIS }
        val history = previous?.conversation()?.takeLast(MAX_HISTORY_MESSAGES) ?: listOf()
        val messages = listOf(Message("system", systemPrompt(ctx.locale))) + history +
                Message("user", inputData)
        // the answer is produced (and spoken) by LocalAiOutput.produce(), called by the evaluator
        return LocalAiOutput(ctx.android.applicationContext, client, messages)
    }

    companion object {
        private const val CONVERSATION_TIMEOUT_MILLIS = 5 * 60 * 1000L
        /** The last three questions and answers. */
        private const val MAX_HISTORY_MESSAGES = 6

        /** Stays the same for every question, so the server reuses it from its prompt cache. */
        fun systemPrompt(locale: Locale): String {
            val language = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
            return "You are Dicio, a voice assistant running on the user's phone. Your answer is " +
                    "read aloud: answer in $language, in one to three short sentences, as you " +
                    "would say it. No lists, no markdown, no emoji. If you do not know, say so."
        }
    }
}
