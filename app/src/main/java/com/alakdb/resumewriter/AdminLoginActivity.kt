package com.alakdb.resumewriter  // Use YOUR package name

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var creditManager: CreditManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_login)

        creditManager = CreditManager(this)

        val etPassword = findViewById<EditText>(R.id.et_admin_password)
        val etEmail = findViewById<EditText>(R.id.et_admin_email) // new email input field
        val btnLogin = findViewById<Button>(R.id.btn_admin_login)
        val btnBack = findViewById<Button>(R.id.btn_back_to_main)

        // Login button
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if(email.isEmpty() || password.isEmpty()) {
                showMessage("Enter email and password")
                return@setOnClickListener
            }

            FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if(task.isSuccessful) {
                        val user = FirebaseAuth.getInstance().currentUser
                        if(user?.email == "alakbd2009@gmail.com") { // <-- your admin email
                            showMessage("Admin access granted!")
                            startActivity(Intent(this, AdminPanelActivity::class.java))
                            finish()
                        } else {
                            showMessage("Not authorized")
                            FirebaseAuth.getInstance().signOut()
                        }
                    } else {
                        showMessage("Login failed: ${task.exception?.message}")
                    }
                }
        }

        // Back button
        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
