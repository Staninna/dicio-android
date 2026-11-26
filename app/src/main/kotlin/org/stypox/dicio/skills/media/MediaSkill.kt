package org.stypox.dicio.skills.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat.getSystemService
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Media

class MediaSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Media>)
    : StandardRecognizerSkill<Media>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Media): SkillOutput {
        val key = when (inputData) {
            is Media.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            is Media.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            is Media.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            is Media.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
        }

        return if (dispatchMediaKey(ctx.android, key)) {
            MediaOutput(performedAction = inputData)
        } else {
            MediaOutput(performedAction = null) // no media session found
        }
    }

    companion object {
        val TAG: String = MediaSkill::class.simpleName!!

        /** Sends a media key to the active media session. Returns false without an audio manager. */
        fun dispatchMediaKey(context: Context, key: Int): Boolean {
            val audioManager = getSystemService(context, AudioManager::class.java)
                ?: return false
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return true
        }
    }
}
