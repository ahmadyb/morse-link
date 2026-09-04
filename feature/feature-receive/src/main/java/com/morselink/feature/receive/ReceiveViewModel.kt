package com.morselink.feature.receive

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.network.DiscoveredPeer
import com.morselink.core.network.TransportSelector
import com.morselink.core.transfer.model.TransportType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val selector: TransportSelector,
    private val holder: ConnectionHolder,
) : ViewModel() {

    private val _status = MutableLiveData("Scan the QR code on the sender's screen")
    val status: LiveData<String> = _status

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    /** QR payload: {"ip":"192.168.43.1","port":54321,"name":"..."} */
    fun onQrScanned(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        val ip = json?.optString("ip").orEmpty()
        val port = json?.optInt("port", 54321) ?: 54321
        if (ip.isBlank()) {
            _status.value = "That code is not a Morselink pairing code"
            return
        }
        connectManually(ip, port)
    }

    fun connectManually(ip: String, port: Int) {
        if (ip.isBlank()) {
            _status.value = "Enter the address shown on the other device"
            return
        }
        _status.value = "Connecting to $ip…"
        viewModelScope.launch {
            val peer = DiscoveredPeer(
                id = "$ip:$port",
                name = "Peer $ip",
                transport = TransportType.LEGACY_WIFI_DIRECT,
                address = ip,
                port = port,
            )
            runCatching { selector.connect(peer) }
                .onSuccess { session ->
                    holder.session = session
                    holder.peer = peer
                    _connected.postValue(true)
                }
                .onFailure { error ->
                    _status.postValue("Could not connect: ${error.message ?: "unknown error"}")
                }
        }
    }
}
