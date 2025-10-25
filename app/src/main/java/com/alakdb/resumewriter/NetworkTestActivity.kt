package com.alakdb.resumewriter

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NetworkTestActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        runBasicNetworkTests()
    }
    
    private fun runBasicNetworkTests() {
        Log.d("NETTEST", "🎯 STARTING BASIC NETWORK TESTS")
        
        // Test 1: Direct OkHttp call (bypass everything)
        testDirectOkHttp()
        
        // Test 2: Test with different URLs
        testMultipleUrls()
    }
    
    private fun testDirectOkHttp() {
        Thread {
            try {
                Log.d("NETTEST", "🔧 TEST 1: Direct OkHttp to Google")
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url("https://www.google.com")
                    .build()
                
                val response = client.newCall(request).execute()
                Log.d("NETTEST", "✅ Google test: ${response.code} ${response.message}")
                
            } catch (e: Exception) {
                Log.e("NETTEST", "❌ Google test failed: ${e.message}")
            }
            
            // Test your API
            testYourApiDirectly()
        }.start()
    }
    
    private fun testYourApiDirectly() {
        try {
            Log.d("NETTEST", "🔧 TEST 2: Direct OkHttp to your API")
            
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url("https://resume-writer-api.onrender.com/health")
                .build()
            
            val response = client.newCall(request).execute()
            Log.d("NETTEST", "✅ Your API test: ${response.code} ${response.message}")
            Log.d("NETTEST", "Response body: ${response.body?.string()}")
            
        } catch (e: Exception) {
            Log.e("NETTEST", "❌ Your API test failed: ${e.message}")
        }
    }
    
    private fun testMultipleUrls() {
        val urls = listOf(
            "https://www.google.com",
            "https://httpbin.org/get", 
            "https://jsonplaceholder.typicode.com/posts/1",
            "https://resume-writer-api.onrender.com/health"
        )
        
        urls.forEach { url ->
            Thread {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .build()
                    
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    Log.d("NETTEST", "✅ $url : ${response.code}")
                } catch (e: Exception) {
                    Log.e("NETTEST", "❌ $url : ${e.message}")
                }
            }.start()
        }
    }
}
