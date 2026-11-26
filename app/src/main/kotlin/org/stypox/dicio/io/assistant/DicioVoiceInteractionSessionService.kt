package org.stypox.dicio.io.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dagger.hilt.android.AndroidEntryPoint
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SpeechOutputDeviceWrapper
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import javax.inject.Inject

/** Only enabled on Android 6+, see `assistant_overlay_supported`. */
@AndroidEntryPoint
class DicioVoiceInteractionSessionService : VoiceInteractionSessionService() {
    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var speechOutputDevice: SpeechOutputDeviceWrapper
    @Inject
    lateinit var skillContext: SkillContextInternal

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistantSession(this, skillEvaluator, sttInputDevice, speechOutputDevice, skillContext)
}
