package com.alakdb.resumewriter

import android.app.Application
import android.util.Log

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Firebase and UserManager early
        val userManager = UserManager(this)
        userManager.emergencySyncWithFirebase()  // ✅ Ensures UID & email are up-to-date
        
        Log.d("MyApplication", "App started & UserManager synced.")
    }
}

