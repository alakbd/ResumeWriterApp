package com.alakdb.resumewriter

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Get device ID (Android ID)
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
     * Improved method to get a more reliable local IP address (IPv4)
     */
    fun getLocalIpAddress(): String {
        try {
            val networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in networkInterfaces) {
                // Optional: Filter out interfaces that are not up or are loopback
                if (!intf.isUp || intf.isLoopback) continue

                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    // Check for IPv4 address that is not a loopback and is site-local
                    if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: "unknown_ip"
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "unknown_ip"
    }

    // Add this method temporarily to MainActivity or RegistrationActivity
private fun testNetworkUtils() {
    Log.d("NetworkTest", "=== TESTING NETWORKUTILS DIRECTLY ===")
    
    try {
        val deviceId = NetworkUtils.getDeviceId(this)
        val deviceInfo = NetworkUtils.getDeviceInfo()
        val userAgent = NetworkUtils.getUserAgent()
        val localIp = NetworkUtils.getLocalIpAddress()
        
        Log.d("NetworkTest", "✅ DeviceId: $deviceId")
        Log.d("NetworkTest", "✅ DeviceInfo: $deviceInfo")
        Log.d("NetworkTest", "✅ UserAgent: $userAgent")
        Log.d("NetworkTest", "✅ LocalIp: $localIp")
        
        // Show in UI for quick verification
        Toast.makeText(this, 
            "NetworkTest - IP: $localIp, Device: ${deviceId.take(8)}...", 
            Toast.LENGTH_LONG
        ).show()
        
    } catch (e: Exception) {
        Log.e("NetworkTest", "❌ NetworkUtils failed: ${e.message}", e)
        Toast.makeText(this, "NetworkTest FAILED: ${e.message}", Toast.LENGTH_LONG).show()
    }
    
    Log.d("NetworkTest", "=== END NETWORKUTILS TEST ===")
    }

    
}
