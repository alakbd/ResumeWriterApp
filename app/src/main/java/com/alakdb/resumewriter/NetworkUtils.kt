package com.alakdb.resumewriter

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    
    // Better IP address detection that works on all networks
    fun getLocalIpAddress(): String {
        return try {
            // Method 1: Try WiFi first (most reliable)
            val wifiIp = getWifiIpAddress()
            if (wifiIp.isNotBlank()) {
                Log.d("NetworkUtils", "📶 Found WiFi IP: $wifiIp")
                return wifiIp
            }
            
            // Method 2: Try mobile data
            val mobileIp = getMobileIpAddress()
            if (mobileIp.isNotBlank()) {
                Log.d("NetworkUtils", "📱 Found Mobile IP: $mobileIp")
                return mobileIp
            }
            
            // Method 3: Try all network interfaces
            val interfaceIp = getAllInterfaceIps()
            if (interfaceIp.isNotBlank()) {
                Log.d("NetworkUtils", "🌐 Found Interface IP: $interfaceIp")
                return interfaceIp
            }
            
            // Method 4: Fallback - external IP service (async)
            fetchExternalIpAsync()
            
            "unknown_ip_${System.currentTimeMillis()}"
            
        } catch (e: Exception) {
            Log.e("NetworkUtils", "❌ IP detection failed: ${e.message}")
            "error_ip_${System.currentTimeMillis()}"
        }
    }
    
    private fun getWifiIpAddress(): String {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ip = wifiInfo?.ipAddress ?: return ""
            
            // Convert IP from integer to string format
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            Log.e("NetworkUtils", "WiFi IP failed: ${e.message}")
            ""
        }
    }
    
    private fun getMobileIpAddress(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val linkProperties = connectivityManager?.getLinkProperties(network)
            
            linkProperties?.linkAddresses?.firstOrNull { address ->
                address.address is Inet4Address && !address.address.isLoopbackAddress
            }?.address?.hostAddress ?: ""
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Mobile IP failed: ${e.message}")
            ""
        }
    }
    
    private fun getAllInterfaceIps(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip.isNotBlank() && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            return ip
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Interface IP failed: ${e.message}")
            ""
        }
    }
    
    // Async method to get external IP (for reference)
    private fun fetchExternalIpAsync() {
        // This runs in background and doesn't block registration
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = withTimeout(5000L) {
                    URL("https://api.ipify.org").readText()
                }
                Log.d("NetworkUtils", "🌍 External IP: $response")
            } catch (e: Exception) {
                Log.d("NetworkUtils", "External IP service unavailable")
            }
        }
    }
    
    // Better Device ID that works everywhere
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return try {
            // Method 1: Android ID (works on all devices)
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            
            if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                Log.d("NetworkUtils", "📱 Using Android ID: ${androidId.take(8)}...")
                return androidId
            }
            
            // Method 2: Build serial + other identifiers
            val buildId = Build.SERIAL + "_" + Build.MODEL.replace(" ", "_")
            if (buildId.isNotBlank() && buildId != "unknown_unknown") {
                Log.d("NetworkUtils", "🔧 Using Build ID: $buildId")
                return "build_${buildId.hashCode().toString().replace("-", "N")}"
            }
            
            // Method 3: Generate persistent UUID
            val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            var uuid = prefs.getString("device_uuid", null)
            if (uuid.isNullOrBlank()) {
                uuid = "uuid_${UUID.randomUUID().toString().substring(0, 8)}"
                prefs.edit().putString("device_uuid", uuid).apply()
            }
            
            Log.d("NetworkUtils", "🆔 Generated UUID: $uuid")
            uuid
            
        } catch (e: Exception) {
            Log.e("NetworkUtils", "❌ Device ID failed: ${e.message}")
            "error_device_${System.currentTimeMillis()}"
        }
    }
}
