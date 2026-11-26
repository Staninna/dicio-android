package org.stypox.dicio.ui.assistant

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer

private class FakeOutput : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext) = ""
    @Composable override fun GraphicalOutput(ctx: SkillContext) {}
}

private fun log(answers: List<QuestionAnswer>, pending: String? = null) = InteractionLog(
    interactions = answers.map { Interaction(skill = null, questionsAnswers = listOf(it)) },
    pendingQuestion = pending?.let { PendingQuestion(it, false, null) },
)

class OverlayStateTest : StringSpec({
    "listening splits the partial input into words" {
        overlayState(SttState.Listening, log(listOf(), "set a  timer"), 0) shouldBe
                OverlayState(OverlayMode.LISTENING, listOf("set", "a", "timer"), null)
    }

    "loading before listening already counts as listening" {
        overlayState(SttState.Loading(thenStartListening = true), log(listOf()), 0).mode shouldBe
                OverlayMode.LISTENING
        overlayState(SttState.Loading(thenStartListening = false), log(listOf()), 0).mode shouldBe
                OverlayMode.IDLE
    }

    "pending input after the microphone closed means thinking" {
        overlayState(SttState.Loaded, log(listOf(), "what time is it"), 0) shouldBe
                OverlayState(OverlayMode.THINKING, listOf("what", "time", "is", "it"), null)
    }

    "an answer given during the session shows with its question" {
        val qa = QuestionAnswer("hi there", FakeOutput())
        overlayState(SttState.Loaded, log(listOf(qa)), 0) shouldBe
                OverlayState(OverlayMode.ANSWERING, listOf("hi", "there"), qa)
    }

    "the answer stays visible when the microphone reopens" {
        val qa = QuestionAnswer("call mom", FakeOutput())
        overlayState(SttState.Listening, log(listOf(qa), "yes"), 0) shouldBe
                OverlayState(OverlayMode.LISTENING, listOf("yes"), qa)
    }

    "answers from before the session opened are ignored" {
        overlayState(SttState.Loaded, log(listOf(QuestionAnswer("old", FakeOutput()))), 1) shouldBe
                OverlayState(OverlayMode.IDLE, listOf(), null)
    }

    "no stt state and nothing said is idle" {
        overlayState(null, log(listOf()), 0).mode shouldBe OverlayMode.IDLE
        overlayState(SttState.NotDownloaded, log(listOf()), 0).mode shouldBe OverlayMode.IDLE
    }

    "answer count sums every question in every interaction" {
        val two = log(listOf(QuestionAnswer("a", FakeOutput()), QuestionAnswer("b", FakeOutput())))
        answerCount(two) shouldBe 2
    }
})
