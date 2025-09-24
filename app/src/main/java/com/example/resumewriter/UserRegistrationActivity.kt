package com.example.resumewriter

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class UserRegistrationActivity : AppCompatActivity() {

    private lateinit var userManager: UserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_registration)

        userManager = UserManager(this)

        val etEmail = findViewById<EditText>(R.id.et_user_email)
        val btnRegister = findViewById<Button>(R.id.btn_register_user)

        btnRegister.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                userManager.registerUser(email) { success, error ->
                    if (success) {
                        Toast.makeText(this, "Registration successful!", Toast.LENGTH_SHORT).show()
                        finish() // Go back to MainActivity
                    } else {
                        Toast.makeText(this, "Registration failed: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a valid email.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
