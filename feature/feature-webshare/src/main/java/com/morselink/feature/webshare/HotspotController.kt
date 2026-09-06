package com.morselink.feature.webshare

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class HotspotResult {
    data class Ready(val ssid: String, val password: String?, val gatewayIp: String) : HotspotResult()
    object ManualSetupRequired : HotspotResult()
    data class Failed(val reason: String) : HotspotResult()
}

interface HotspotController {
    fun start(onResult: (HotspotResult) -> Unit)
    fun stop()
}

/**
 * §4.5 — hotspot creation is version gated. Note that client Wi-Fi and hotspot
 * mode share one radio on virtually all supported hardware, so turning the
 * hotspot on is expected to drop an existing Wi-Fi connection. That is normal,
 * not an error, and the UI copy says so.
 */
class HotspotControllerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: com.morselink.core.network.NetworkUtils,
) {
    fun create(): HotspotController = if (Build.VERSION.SDK_INT >= 26) {
        ModernHotspotController(context, network)
    } else {
        LegacyHotspotController(context, network)
    }
}

class ModernHotspotController(
    private val context: Context,
    private val network: com.morselink.core.network.NetworkUtils,
) : HotspotController {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val manualScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    override fun start(onResult: (HotspotResult) -> Unit) {
        val manager = wifiManager
        if (manager == null) {
            onResult(HotspotResult.Failed("Wi-Fi is unavailable on this device"))
            return
        }
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                this@ModernHotspotController.reservation = reservation
                val config = reservation?.wifiConfiguration
                val gateway = network.hotspotGatewayIp()
                    ?: com.morselink.core.network.NetworkUtils.DEFAULT_GATEWAY
                onResult(
                    HotspotResult.Ready(
                        ssid = config?.SSID ?: "Morselink hotspot",
                        password = config?.preSharedKey,
                        gatewayIp = gateway,
                    )
                )
            }

            override fun onStopped() {
                reservation = null
            }

            override fun onFailed(reason: Int) {
                onResult(HotspotResult.Failed("Hotspot failed to start (code $reason)"))
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
            } else {
                manager.startLocalOnlyHotspot(callback, null)
            }
        }.onFailure { error ->
            // Several OEM builds reject startLocalOnlyHotspot with a
            // SecurityException regardless of what the manifest declares. That
            // used to surface as a raw "package=... UID=... does not have
            // permission" message and nothing else, leaving the user stuck.
            // Open the hotspot settings and wait for it to come up instead.
            android.util.Log.w("Morselink", "startLocalOnlyHotspot rejected", error)
            fallbackToManual(onResult)
        }
    }

    /** Ask the user to switch the hotspot on, then poll until it appears. */
    private fun fallbackToManual(onResult: (HotspotResult) -> Unit) {
        onResult(HotspotResult.ManualSetupRequired)
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        manualScope.launch {
            repeat(40) {
                delay(1_500)
                val ip = network.localIpAddress()
                if (ip != null && (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49."))) {
                    onResult(
                        HotspotResult.Ready(
                            ssid = "Phone hotspot",
                            password = null,
                            gatewayIp = ip,
                        )
                    )
                    return@launch
                }
            }
        }
    }

    override fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }
}

class LegacyHotspotController(
    private val context: Context,
    private val network: com.morselink.core.network.NetworkUtils,
) : HotspotController {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    override fun start(onResult: (HotspotResult) -> Unit) {
        // No programmatic hotspot API exists before API 26: ask, then wait for it.
        onResult(HotspotResult.ManualSetupRequired)
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        scope.launch {
            repeat(40) {
                delay(1_500)
                val ip = network.localIpAddress()
                if (ip != null && (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49."))) {
                    onResult(HotspotResult.Ready(ssid = "Phone hotspot", password = null, gatewayIp = ip))
                    return@launch
                }
            }
            onResult(HotspotResult.Failed("Hotspot not detected yet"))
        }
    }

    override fun stop() {
        scope.launch { /* nothing to tear down: the user owns the hotspot on API < 26 */ }
    }
}
