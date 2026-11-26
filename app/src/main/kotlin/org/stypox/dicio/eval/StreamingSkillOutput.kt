package org.stypox.dicio.eval

import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.skill.SkillOutput

/**
 * A [SkillOutput] whose answer is still being produced when it is shown, for example an answer
 * that a language model writes word by word. The [SkillEvaluator] shows the output right away,
 * then calls [produce] once, which hands each piece of speech over as soon as it is ready, so the
 * first sentence is spoken while the rest is still being written.
 */
interface StreamingSkillOutput : SkillOutput {
    /** `true` until [produce] has finished. */
    val isProducing: StateFlow<Boolean>

    /** Produces the answer, calling [speak] with every piece of speech (e.g. a sentence). */
    suspend fun produce(speak: suspend (String) -> Unit)
}
