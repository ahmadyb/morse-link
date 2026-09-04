package com.morselink.feature.webshare

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.network.NetworkUtils
import com.morselink.core.network.SessionServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebShareState(
    val running: Boolean = false,
    val url: String = "",
    val hotspotActive: Boolean = false,
    val hotspotInfo: String = "",
)

@HiltViewModel
class WebShareViewModel @Inject constructor(
    private val server: WebShareServer,
    private val hotspotFactory: HotspotControllerFactory,
    private val nsd: NsdAdvertiser,
    private val network: NetworkUtils,
    private val settings: SettingsStore,
    private val serviceStarter: SessionServiceController,
) : ViewModel() {

    private var controller: HotspotController = hotspotFactory.create()

    private val _state = MutableLiveData(WebShareState())
    val state: LiveData<WebShareState> = _state

    private val _message = MutableLiveData<String?>(null)
    val message: LiveData<String?> = _message

    init {
        server.onStopRequest = { stop() }
    }

    fun toggleServer() {
        if (_state.value?.running == true) stop() else start()
    }

    private fun start() {
        val ip = network.localIpAddress()
        if (ip.isNullOrBlank()) {
            _message.value = "Connect to Wi-Fi (or start a hotspot) first"
            return
        }
        val started = server.startServer()
        if (!started) {
            _message.value = "Could not start the WebShare server on port ${WebShareServer.PORT}"
            return
        }
        viewModelScope.launch {
            val deviceName = settings.settings.first().deviceName
            nsd.start(WebShareServer.PORT, deviceName)
            serviceStarter.start()
            _state.postValue(
                _state.value!!.copy(
                    running = true,
                    url = "http://$ip:${WebShareServer.PORT}",
                )
            )
        }
    }

    private fun stop() {
        server.stopServer()
        nsd.stop()
        serviceStarter.stop()
        _state.postValue(_state.value!!.copy(running = false))
    }

    fun toggleHotspot() {
        if (_state.value?.hotspotActive == true) {
            controller.stop()
            _state.postValue(_state.value!!.copy(hotspotActive = false, hotspotInfo = ""))
            return
        }
        controller.start { result ->
            when (result) {
                is HotspotResult.Ready -> {
                    _state.postValue(
                        _state.value!!.copy(
                            hotspotActive = true,
                            hotspotInfo = "Hotspot: ${result.ssid}" +
                                result.password?.let { " · password $it" }.orEmpty(),
                        )
                    )
                    if (_state.value?.running == true) {
                        _state.postValue(_state.value!!.copy(url = "http://${result.gatewayIp}:${WebShareServer.PORT}"))
                    } else {
                        start()
                    }
                }
                HotspotResult.ManualSetupRequired ->
                    _message.postValue("Turn on the hotspot in Wi-Fi settings, then come back")
                is HotspotResult.Failed -> _message.postValue(result.reason)
            }
        }
    }

    override fun onCleared() {
        server.stopServer()
        nsd.stop()
        controller.stop()
        super.onCleared()
    }
}
