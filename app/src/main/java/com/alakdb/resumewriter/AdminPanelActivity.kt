package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityAdminPanelBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.FirebaseAuth

class AdminPanelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var creditManager: CreditManager
    private val db = Firebase.firestore
    private var selectedUserId: String = ""
    private var selectedUserEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        creditManager = CreditManager(this)

        // Check admin access
        if (!creditManager.isAdminMode()) {
            showMessage("Admin access required!")
            finish()
            return
        }

        setupUI()
        loadUsers()
    }

    private fun setupUI() {
        val btnAdd10 = findViewById<Button>(R.id.btn_admin_add_10)
        val btnAdd50 = findViewById<Button>(R.id.btn_admin_add_50)
        val btnSet100 = findViewById<Button>(R.id.btn_admin_set_100)
        val btnReset = findViewById<Button>(R.id.btn_admin_reset)
        val btnGenerateFree = findViewById<Button>(R.id.btn_admin_generate_free)
        val btnUserStats = findViewById<Button>(R.id.btn_admin_stats)
        val btnLogout = findViewById<Button>(R.id.btn_admin_logout)

        // User selection spinner
        binding.spUserSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val selected = parent.getItemAtPosition(position).toString()
                    selectedUserEmail = selected
                    loadUserData(selectedUserEmail)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Button click listeners
        btnAdd10.setOnClickListener { modifyUserCredits(10, "add") }
        btnAdd50.setOnClickListener { modifyUserCredits(50, "add") }
        btnSet100.setOnClickListener { modifyUserCredits(100, "set") }
        btnReset.setOnClickListener { modifyUserCredits(0, "reset") }
        btnGenerateFree.setOnClickListener { generateFreeCV() }
        btnUserStats.setOnClickListener { showUserStats() }
        btnLogout.setOnClickListener { logoutAdmin() }
    }

    private fun loadUsers() {
        db.collection("users").get()
            .addOnSuccessListener { documents ->
                val users = mutableListOf("Select a user...")
                documents.forEach { document ->
                    document.getString("email")?.let { email ->
                        users.add(email)
                    }
                }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, users)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spUserSelector.adapter = adapter
            }
            .addOnFailureListener { showMessage("Failed to load users") }
    }

    private fun loadUserData(email: String) {
        db.collection("users")
            .whereEqualTo("email", email)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    showMessage("User not found")
                    return@addOnSuccessListener
                }

                val document = documents.documents[0]
                selectedUserId = document.id

                val available = document.getLong("availableCredits") ?: 0
                val used = document.getLong("usedCredits") ?: 0
                val total = document.getLong("totalCreditsEarned") ?: 0

                updateUserDisplay(available.toInt(), used.toInt(), total.toInt())
            }
            .addOnFailureListener { showMessage("Failed to load user data") }
    }

    private fun modifyUserCredits(amount: Int, operation: String) {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        when (operation) {
            "add" -> creditManager.adminAddCreditsToUser(selectedUserId, amount) { success ->
                if (success) {
                    showMessage("Added $amount credits to $selectedUserEmail")
                    loadUserData(selectedUserEmail)
                } else {
                    showMessage("Failed to add credits")
                }
            }
            "set" -> creditManager.adminSetUserCredits(selectedUserId, amount) { success ->
                if (success) {
                    showMessage("Set credits to $amount for $selectedUserEmail")
                    loadUserData(selectedUserEmail)
                } else {
                    showMessage("Failed to set credits")
                }
            }
            "reset" -> creditManager.adminResetUserCredits(selectedUserId) { success ->
                if (success) {
                    showMessage("Reset credits for $selectedUserEmail")
                    loadUserData(selectedUserEmail)
                } else {
                    showMessage("Failed to reset credits")
                }
            }
        }
    }

    private fun generateFreeCV() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        db.collection("users").document(selectedUserId).update(
            "usedCredits", com.google.firebase.firestore.FieldValue.increment(1),
            "lastUpdated", System.currentTimeMillis()
        ).addOnSuccessListener {
            showMessage("Free CV generated for $selectedUserEmail")
            loadUserData(selectedUserEmail)
        }.addOnFailureListener {
            showMessage("Failed to generate CV")
        }
    }

    private fun showUserStats() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        db.collection("users").document(selectedUserId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val stats = """
                        Email: ${document.getString("email")}
                        Available Credits: ${document.getLong("availableCredits") ?: 0}
                        Used Credits: ${document.getLong("usedCredits") ?: 0}
                        Total Credits: ${document.getLong("totalCreditsEarned") ?: 0}
                        Created: ${document.getLong("createdAt")?.let { 
                            java.text.SimpleDateFormat("MMM dd, yyyy").format(it) } ?: "Unknown"}
                    """.trimIndent()

                    AlertDialog.Builder(this)
                        .setTitle("User Statistics")
                        .setMessage(stats)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
    }

    private fun logoutAdmin() {
        // Sign out from Firebase Auth
        FirebaseAuth.getInstance().signOut()

        showMessage("Admin logged out")

        // Return to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }


    private fun updateUserDisplay(available: Int, used: Int, total: Int) {
        binding.tvUserEmail.text = "User: $selectedUserEmail"
        binding.tvAvailableCredits.text = "Available: $available"
        binding.tvUsedCredits.text = "Used: $used"
        binding.tvTotalCredits.text = "Total: $total"
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
