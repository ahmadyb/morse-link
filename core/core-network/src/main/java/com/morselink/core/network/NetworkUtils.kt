package com.morselink.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** Local network helpers: our own LAN address, the hotspot gateway, port checks. */
@Singleton
class NetworkUtils @Inject constructor() {

    /** The phone's own IPv4 address on the active Wi-Fi interface. */
    fun localIpAddress(): String? {
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (address.isLoopbackAddress || address !is Inet4Address) continue
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("192.168.43.") || host.startsWith("192.168.49.")) return host
                }
            }
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (address.isLoopbackAddress || address !is Inet4Address) continue
                    return address.hostAddress
                }
            }
        }
        return null
    }

    /** Typical soft-AP gateway address — used by the WebShare URL when we host the hotspot. */
    fun hotspotGatewayIp(): String? {
        val local = localIpAddress()
        if (local != null && (local.startsWith("192.168.43.") || local.startsWith("192.168.49."))) {
            return local
        }
        return runCatching {
            val parts = local?.split(".")
            if (parts != null && parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else null
        }.getOrNull() ?: DEFAULT_GATEWAY
    }

    fun wifiIpAddress(context: Context): Int? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        return runCatching { wifiManager?.connectionInfo?.ipAddress }.getOrNull()
    }

    fun ipFromInt(address: Int): String =
        String.format(
            java.util.Locale.US,
            "%d.%d.%d.%d",
            address and 0xff,
            address shr 8 and 0xff,
            address shr 16 and 0xff,
            address shr 24 and 0xff,
        )

    fun isWifiConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return if (Build.VERSION.SDK_INT >= 23) {
            val network = connectivity?.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            connectivity?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    companion object {
        const val DEFAULT_GATEWAY = "192.168.43.1"
        const val WEBSHARE_PORT = 33455
    }
}
