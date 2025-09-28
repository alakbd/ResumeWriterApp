package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityAdminLoginBinding
import com.google.firebase.auth.FirebaseAuth

class AdminLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.btnAdminLogin.setOnClickListener {
            val email = binding.etAdminEmail.text.toString().trim()
            val password = binding.etAdminPassword.text.toString().trim()

            if (validateInput(email, password)) {
                attemptAdminLogin(email, password)
            }
        }

        binding.btnBackToMain.setOnClickListener {
            finish()
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            binding.etAdminEmail.error = "Admin email required"
            return false
        }

        if (password.isEmpty()) {
            binding.etAdminPassword.error = "Password required"
            return false
        }

        return true
    }

    private fun attemptAdminLogin(email: String, password: String) {
        binding.btnAdminLogin.isEnabled = false
        binding.btnAdminLogin.text = "Authenticating..."

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                binding.btnAdminLogin.isEnabled = true
                binding.btnAdminLogin.text = "Login"

                if (task.isSuccessful) {
                    val user = auth.currentUser
                    val adminEmails = listOf("alakbd2009@gmail.com", "admin@resumewriter.com")
                    
                    if (user != null && adminEmails.contains(user.email)) {
                        // ✅ Mark admin mode ON
                        creditManager.setAdminMode(true)
                        
                        showMessage("Admin access granted!")
                        startActivity(Intent(this, AdminPanelActivity::class.java))
                        finish()
                    } else {
                        showMessage("Access denied: Not an admin account")
                        auth.signOut()
                    }
                } else {
                    showMessage("Login failed: ${task.exception?.message}")
                }
            }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
