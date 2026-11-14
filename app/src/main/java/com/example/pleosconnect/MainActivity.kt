package com.example.pleosconnect

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.example.pleosconnect.ui.DriveMateScreen
import com.example.pleosconnect.ui.DriveMateViewModel
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import com.example.pleosconnect.data.profile.AppProfileRepository
import com.example.pleosconnect.integration.LiveNavigationIntegration
import com.example.pleosconnect.integration.LiveVehicleReadIntegration
import com.example.pleosconnect.integration.LiveVehicleControlIntegration

class MainActivity : ComponentActivity() {
    private val localAudio by lazy { com.example.pleosconnect.data.media.LocalAudioPlayer(application) }
    private val mediaIntegration by lazy { com.example.pleosconnect.integration.LiveMediaIntegration.create(application) }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::selectLocalAudio) // Selection only; never starts playback from an activity result.
    }
    private fun chooseAudio() {
        if (model.canSelectLocalAudio()) runCatching { audioPicker.launch(arrayOf("audio/*")) }
            .onFailure { model.localAudioPickerUnavailable() }
    }
    private fun chooseTestAudio() {
        if (model.canSelectLocalAudio()) runCatching {
            model.selectLocalAudio(com.example.pleosconnect.data.media.DebugAudioSample.uri(application))
        }.onFailure { model.localAudioPickerUnavailable() }
    }
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.setVehicleRead(true) // Other fields remain readable even if speed permission was denied.
    }
    private fun vehicleRead(enabled: Boolean) {
        if (enabled && ContextCompat.checkSelfPermission(this, "android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED)
            speedPermission.launch("android.car.permission.CAR_SPEED")
        else model.setVehicleRead(enabled)
    }
    private val model by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DriveMateViewModel::class.java))
                return DriveMateViewModel(catalog = AppProfileRepository.get(application),
                    liveNavigation = LiveNavigationIntegration.create(application),
                    liveVehicle = LiveVehicleReadIntegration.create(application),
                    vehicleControl = LiveVehicleControlIntegration.create(application),
                    liveMedia = mediaIntegration,
                    localAudio = localAudio,
                    liveVoice = com.example.pleosconnect.integration.LiveVoiceIntegration(application, localAudio, mediaIntegration),
                    nearbyTransport = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        com.example.pleosconnect.pleos.navigation.DebugNearbyTransport() else null) as T
            }
        })[DriveMateViewModel::class.java]
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsState()
            val navigation by model.navigationState.collectAsState()
            val vehicle by model.vehicleState.collectAsState()
            val controls by model.vehicleControlState.collectAsState()
            val media by model.mediaState.collectAsState()
            val local by model.localAudioState.collectAsState()
            val realVoice by model.liveVoiceEnabled.collectAsState()
            val voiceNotice by model.voiceConnectionNotice.collectAsState()
            val nearby by model.nearbyState.collectAsState()
            PleosConnectTheme {
                DriveMateScreen(state, model::act, { model.reset(it) }, { model.reset(switchProfile = true) },
                    model::registerProfile, model::selectProfile, model::saveProfile, model::deleteProfile, model::reloadProfiles,
                    navigation, model::setLiveNavigation, model::setLiveDestination, model::resumeLiveNavigation, model::loadNavigationDestinations,
                    vehicle, ::vehicleRead, controls, model::prepareVehicleControl, model::prepareVehicleSetting,
                    model::restoreVehicleSetting, model::confirmVehicleSetting, model::cancelVehicleSetting, model::stopVehicleControl,
                    media, model.mediaActions, local, ::chooseAudio, model::playLocalAudio, model::stopLocalAudio,
                    if (com.example.pleosconnect.data.media.DebugAudioSample.available) ::chooseTestAudio else null,
                    model.liveVoiceAvailable, realVoice, model::setLiveVoice, model::voiceCheck, voiceNotice,
                    model.nearbyAvailable, nearby, model::prepareNearby, model::confirmNearby, model::cancelNearby)
            }
        }
    }
    override fun onResume() { super.onResume(); model.setForeground(true) }
    override fun onPause() { model.setForeground(false); super.onPause() }
}
