package com.morselink.feature.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.morselink.core.data.prefs.SettingsStore
import com.morselink.core.network.DiscoveredPeer
import com.morselink.core.network.TransportSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val selector: TransportSelector,
) : ViewModel() {

    val deviceName: LiveData<String> = settings.settings
        .map { it.deviceName }
        .asLiveData()

    private val _peers = MutableLiveData<List<DiscoveredPeer>>(emptyList())
    val peers: LiveData<List<DiscoveredPeer>> = _peers

    private var discoveryJob: Job? = null

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            runCatching {
                selector.discover { peer ->
                    val current = _peers.value.orEmpty()
                    if (current.none { it.id == peer.id }) {
                        _peers.postValue(current + peer)
                    }
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        viewModelScope.launch { selector.stop() }
    }

    override fun onCleared() {
        stopDiscovery()
        super.onCleared()
    }
}
