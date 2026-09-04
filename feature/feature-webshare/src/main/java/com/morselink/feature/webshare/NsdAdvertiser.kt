package com.morselink.feature.webshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §4.5 — mDNS/NSD advertisement is a convenience layer only: the raw
 * http://ip:port is always shown on screen, and a MulticastLock is held for the
 * whole session because multicast is commonly blocked without one (especially
 * when the phone itself is hosting the hotspot).
 */
@Singleton
class NsdAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var registered = false

    fun start(port: Int, deviceName: String): Boolean {
        val manager = nsdManager ?: return false
        stop()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("morselink-mdns")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                registered = false
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                registered = true
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                registered = false
            }
        }
        listener = registration
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { if (registered) nsdManager?.unregisterService(listener!!) }
        registered = false
        listener = null
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_morselink._tcp."
    }
}
