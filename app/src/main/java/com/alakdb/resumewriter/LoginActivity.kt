package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var btnForgotPassword: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        val etEmail = findViewById<EditText>(R.id.et_login_email)
        val etPassword = findViewById<EditText>(R.id.et_login_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnGoToRegister = findViewById<Button>(R.id.btn_go_to_register)

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showMessage("Enter email and password")
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        showMessage("Login successful!")
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        showMessage("Login failed: ${task.exception?.message}")
                    }
                }
        }
        btnForgotPassword = findViewById(R.id.btn_forgot_password)

        btnForgotPassword.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                showMessage("Enter your email to reset password")
                return@setOnClickListener
            }

            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        showMessage("Password reset email sent to $email")
                    } else {
                        showMessage("Error: ${task.exception?.message}")
            }
        }
}

        btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
        }
    }

    private fun showMessage(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
