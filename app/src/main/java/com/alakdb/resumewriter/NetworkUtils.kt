package com.alakdb.resumewriter

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    
    /**
     * Get device ID (Android ID) - THIS ONE NEEDS Context
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }
    
    /**
     * Get device model and manufacturer
     */
    fun getDeviceInfo(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }
    
    /**
     * Get user agent string
     */
    fun getUserAgent(): String {
        return System.getProperty("http.agent") ?: "Unknown"
    }
    
    /**
     * Get local IP address - NO CONTEXT NEEDED
     */
    fun getLocalIpAddress(): String {  // ⭐⭐⭐ REMOVED Context parameter
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: "unknown_ip"
                    }
                }
            }
            "unknown_ip"
        } catch (e: Exception) {
            "unknown_ip"
        }
    }
}
