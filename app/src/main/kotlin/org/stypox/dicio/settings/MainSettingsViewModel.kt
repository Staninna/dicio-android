package org.stypox.dicio.settings

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.io.local_ai.LocalAiPairing
import org.stypox.dicio.io.wake.oww.OpenWakeWordDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import javax.inject.Inject

@HiltViewModel
class MainSettingsViewModel @Inject constructor(
    application: Application,
    private val wakeDeviceWrapper: WakeDeviceWrapper?,
    private val dataStore: DataStore<UserSettings>,
    private val localAiPairing: LocalAiPairing,
) : AndroidViewModel(application) {
    // run blocking because the settings screen cannot start if settings have not been loaded yet
    val settingsState = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    private fun updateData(transform: (UserSettings.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData {
                it.toBuilder()
                    .apply(transform)
                    .build()
            }
        }
    }

    val isHeyDicio: StateFlow<Boolean> = wakeDeviceWrapper?.isHeyDicio ?: MutableStateFlow(true)

    fun addOwwUserWakeFile(uri: Uri) {
        viewModelScope.launch {
            OpenWakeWordDevice.addUserWakeFile(getApplication(), uri)
            wakeDeviceWrapper?.reinitialize()
        }
    }

    fun removeOwwUserWakeFile() {
        viewModelScope.launch {
            OpenWakeWordDevice.removeUserWakeFile(getApplication())
            wakeDeviceWrapper?.reinitialize()
        }
    }

    val localAiPairingState: StateFlow<LocalAiPairing.Pairing?> = localAiPairing.pairing

    fun isLocalAiServerInstalled(): Boolean = localAiPairing.isServerInstalled()

    /** Stores the result of SmolChat's pairing activity; returns whether pairing succeeded. */
    fun onLocalAiPairingResult(resultCode: Int, data: Intent?): Boolean =
        resultCode == Activity.RESULT_OK && localAiPairing.onPairingResult(data)

    fun unpairLocalAi() = localAiPairing.unpair()

    fun setLocalAiEnabled(value: Boolean) =
        updateData { it.setLocalAiDisabled(!value) }

    fun setLanguage(value: Language) =
        updateData { it.setLanguage(value) }
    fun setTheme(value: Theme) =
        updateData { it.setTheme(value) }
    fun setDynamicColors(value: Boolean) =
        updateData { it.setDynamicColors(value) }
    fun setInputDevice(value: InputDevice) =
        updateData { it.setInputDevice(value) }
    fun setWakeDevice(value: WakeDevice) =
        updateData { it.setWakeDevice(value) }
    fun setSpeechOutputDevice(value: SpeechOutputDevice) =
        updateData { it.setSpeechOutputDevice(value) }
    fun setSttPlaySound(value: SttPlaySound) =
        updateData { it.setSttPlaySound(value) }
    fun setSttSilenceDuration(value: Int) =
        updateData { it.setSttSilenceDuration(value) }
    fun setAutoFinishSttPopup(value: Boolean) =
        updateData { it.setAutoFinishSttPopup(value) }
}
