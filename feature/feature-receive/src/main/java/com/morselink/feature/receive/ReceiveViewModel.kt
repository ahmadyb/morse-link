package com.morselink.feature.receive

import android.util.Log

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.network.DiscoveredPeer
import com.morselink.core.network.PairingPayload
import com.morselink.core.network.TransportSelector
import com.morselink.core.transfer.model.TransportType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val selector: TransportSelector,
    private val holder: ConnectionHolder,
    private val settings: SettingsStore,
) : ViewModel() {

    private var connectJob: kotlinx.coroutines.Job? = null

    private val _status = MutableLiveData("Scan the QR code on the sender's screen")
    val status: LiveData<String> = _status

    // One-shot, not state. A plain LiveData replays its last value to every
    // new observer, so cancelling out of the transfer screen - which pops back
    // to this one - re-fired "connected" and navigated straight back in. Cancel
    // looked like it did nothing but bounce, and the only way out was to kill
    // the app. The fragment clears the flag as it consumes it.
    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    /** Clears a consumed connection so returning here does not re-navigate. */
    fun consumeConnected() {
        if (_connected.value == true) _connected.value = false
    }

    /** QR payload: {"ip":"192.168.43.1","port":54321,"name":"...","transport":"..."} */
    fun onQrScanned(payload: String) {
        val parsed = PairingPayload.parse(payload)
        if (parsed == null) {
            _status.value = "That code is not a Morselink pairing code"
            return
        }
        connectManually(parsed.address, parsed.port)
    }

    fun connectManually(ip: String, port: Int) {
        if (ip.isBlank()) {
            _status.value = "Enter the address shown on the other device"
            return
        }
        _status.value = "Connecting to $ip…"
        connectJob = viewModelScope.launch {
            val name = runCatching { settings.current().deviceName }
                .getOrDefault("Morselink")
                .ifBlank { "Morselink" }
            Log.d("Morselink", "receiver: joining $ip:$port as $name")
            val session = selector.joinDirect(ip, port, name)
            if (session == null) {
                Log.w("Morselink", "receiver: no answer from $ip:$port")
                _status.postValue(
                    "No answer from $ip:$port. Check the sender is still showing its " +
                        "code and that both phones are on the same network."
                )
                return@launch
            }
            Log.d("Morselink", "receiver: connected to $ip")
            holder.attach(session, session.peer)
            _status.postValue("Connected to $ip — waiting for files")
            _connected.postValue(true)
        }
    }

    /** Abandon a connect attempt that has not answered yet. */
    fun cancel() {
        connectJob?.cancel()
        connectJob = null
        _status.postValue("Cancelled")
        viewModelScope.launch { runCatching { holder.close() } }
    }
}
