package com.nuvio.tv.core.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIpAddress {

    fun get(context: Context): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    try {
                        iface.isUp && !iface.isLoopback && !iface.isVirtual
                    } catch (_: Exception) {
                        false
                    }
                }
                .sortedByDescending { iface ->
                    val name = iface.name.lowercase()
                    when {
                        name.startsWith("wlan") -> 4
                        name.startsWith("eth") -> 3
                        name.startsWith("en") -> 2
                        !name.contains("dummy") && !name.contains("p2p") && !name.contains("tun") -> 1
                        else -> 0
                    }
                }

            val validIp = interfaces.flatMap { iface ->
                try {
                    iface.inetAddresses.toList()
                } catch (_: Exception) {
                    emptyList()
                }
            }.firstOrNull { addr ->
                !addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress
            }?.hostAddress

            if (validIp != null) return validIp

            // Fallback: try WifiManager
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                formatIp(wifiIp)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
