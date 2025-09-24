package com.example.resumewriter  // Use YOUR package name

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AdminLoginActivity : AppCompatActivity() {
    private lateinit var creditManager: CreditManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_login)
        
        creditManager = CreditManager(this)
        
        val etPassword = findViewById<EditText>(R.id.et_admin_password)
        val btnLogin = findViewById<Button>(R.id.btn_admin_login)
        val btnBack = findViewById<Button>(R.id.btn_back_to_main)
        
        btnLogin.setOnClickListener {
            val password = etPassword.text.toString()
            if (creditManager.authenticateAdmin(password)) {
                showMessage("Admin access granted!")
                startActivity(Intent(this, AdminPanelActivity::class.java))
                finish()
            } else {
                showMessage("Invalid admin password!")
            }
        }
        
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
