package org.stypox.dicio.ui.assistant

import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.QuestionAnswer

enum class OverlayMode {
    /** Nothing is happening, e.g. the STT model is loading or nothing was said. */
    IDLE,
    /** The microphone is open. */
    LISTENING,
    /** The user finished talking and a skill is being evaluated. */
    THINKING,
    /** A skill answered and the microphone is closed. */
    ANSWERING,
}

/**
 * What the assistant overlay shows. Derived from the STT state and the [InteractionLog], so the
 * overlay never keeps its own copy of the conversation.
 *
 * @param words the user's words: live while listening, the answered question afterwards
 * @param answer the latest answer given since the overlay opened, kept while the microphone
 * reopens so that e.g. a call confirmation stays visible while the user says "yes"
 */
data class OverlayState(
    val mode: OverlayMode,
    val words: List<String>,
    val answer: QuestionAnswer?,
)

/**
 * @param answersAtShow how many answers the log held when the overlay opened, so that answers
 * given before then (e.g. in the main activity) never show up in the overlay
 */
fun overlayState(sttState: SttState?, log: InteractionLog, answersAtShow: Int): OverlayState {
    val pending = log.pendingQuestion
    val answer = latestAnswer(log, answersAtShow)
    val listening = sttState == SttState.Listening ||
            sttState == SttState.WaitingForResult ||
            (sttState is SttState.Loading && sttState.thenStartListening)

    return when {
        listening -> OverlayState(OverlayMode.LISTENING, splitWords(pending?.userInput), answer)
        pending != null -> OverlayState(OverlayMode.THINKING, splitWords(pending.userInput), answer)
        answer != null -> OverlayState(OverlayMode.ANSWERING, splitWords(answer.question), answer)
        else -> OverlayState(OverlayMode.IDLE, listOf(), null)
    }
}

fun answerCount(log: InteractionLog): Int = log.interactions.sumOf { it.questionsAnswers.size }

private fun latestAnswer(log: InteractionLog, answersAtShow: Int): QuestionAnswer? {
    if (answerCount(log) <= answersAtShow) {
        return null
    }
    return log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()
}

private fun splitWords(text: String?): List<String> =
    text?.split(' ')?.filter { it.isNotBlank() } ?: listOf()
