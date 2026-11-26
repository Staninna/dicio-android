package org.stypox.dicio.skills.fallback.local_ai

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.io.local_ai.LocalAiClient

/**
 * The fallback used when no skill matches and Dicio is paired with the SmolChat server: the
 * local language model answers the question.
 */
class LocalAiFallbackInfo(private val client: LocalAiClient) : SkillInfo("local_ai") {
    override fun name(context: Context) =
        context.getString(R.string.skill_fallback_name_local_ai)

    override fun sentenceExample(context: Context) =
        ""

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.AutoAwesome)

    override fun build(ctx: SkillContext): Skill<*> {
        return LocalAiFallbackSkill(this, client)
    }
}
