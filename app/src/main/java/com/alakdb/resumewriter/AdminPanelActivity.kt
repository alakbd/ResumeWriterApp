package com.alakdb.resumewriter

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityAdminPanelBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

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

        if (!creditManager.isAdminMode()) {
            showMessage("Admin access required!")
            finish()
            return
        }

        setupUI()
        loadUsers()
    }

    private fun setupUI() {
        binding.btnAdminAdd10.setOnClickListener { modifyUserCredits(10, "add") }
        binding.btnAdminAdd50.setOnClickListener { modifyUserCredits(50, "add") }
        binding.btnAdminSet100.setOnClickListener { modifyUserCredits(100, "set") }
        binding.btnAdminReset.setOnClickListener { modifyUserCredits(0, "reset") }
        binding.btnAdminGenerateFree.setOnClickListener { generateFreeCV() }
        binding.btnAdminStats.setOnClickListener { showUserStats() }
        binding.btnAdminLogout.setOnClickListener { logoutAdmin() }

        // User selection spinner
        binding.spUserSelector.onItemSelectedListener = 
            android.widget.AdapterView.OnItemSelectedListener { parent, _, position, _ ->
                if (position > 0) {
                    val selected = parent.getItemAtPosition(position).toString()
                    selectedUserEmail = selected
                    loadUserData(selected)
                }
            }
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
        creditManager.isAdminMode() // This will be handled by auth signout
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        showMessage("Admin logged out")
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

       
