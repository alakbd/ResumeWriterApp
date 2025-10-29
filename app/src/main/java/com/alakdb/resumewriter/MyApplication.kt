package com.alakdb.resumewriter

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val userManager = UserManager(this)
        userManager.emergencySyncWithFirebase()  // Sync UID & email immediately
    }
}
