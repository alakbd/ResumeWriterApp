package com.alakdb.resumewriter

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityUserRegistrationBinding
import android.content.Intent
import com.alakdb.resumewriter.MainActivity

class UserRegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserRegistrationBinding
    private lateinit var userManager: UserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityUserRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showMessage("Please enter email and password.")
                return@setOnClickListener
            }

            userManager.registerUser(email, password) { success, error ->
                if (success) {
                    showMessage("Registration successful!")
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    showMessage("Registration failed: ${error ?: "Unknown error"}")
                }
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

