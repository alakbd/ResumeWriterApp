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
    private val usersList = mutableListOf<Pair<String, String>>() // docId -> email

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
    }

    private fun loadUsers() {
        db.collection("users").get()
            .addOnSuccessListener { documents ->
                usersList.clear()
                usersList.add("" to "Select a user...") // default

                for (doc in documents) {
                    val email = doc.getString("email") ?: continue
                    usersList.add(doc.id to email)
                }

                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    usersList.map { it.second }
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spUserSelector.adapter = adapter

                binding.spUserSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        if (position == 0) {
                            selectedUserId = ""
                            selectedUserEmail = ""
                            updateUserDisplay(0, 0, 0)
                            return
                        }
                        val pair = usersList[position]
                        selectedUserId = pair.first
                        selectedUserEmail = pair.second
                        loadUserDataById(selectedUserId)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
            .addOnFailureListener {
                showMessage("Failed to load users")
            }
    }

    private fun loadUserDataById(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    showMessage("User not found")
                    return@addOnSuccessListener
                }

                val available = document.getLong("availableCredits")?.toInt() ?: 0
                val used = document.getLong("usedCredits")?.toInt() ?: 0
                val total = document.getLong("totalCreditsEarned")?.toInt() ?: 0

                updateUserDisplay(available, used, total)
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
                    loadUserDataById(selectedUserId)
                } else showMessage("Failed to add credits")
            }
            "set" -> creditManager.adminSetUserCredits(selectedUserId, amount) { success ->
                if (success) {
                    showMessage("Set credits to $amount for $selectedUserEmail")
                    loadUserDataById(selectedUserId)
                } else showMessage("Failed to set credits")
            }
            "reset" -> creditManager.adminResetUserCredits(selectedUserId) { success ->
                if (success) {
                    showMessage("Reset credits for $selectedUserEmail")
                    loadUserDataById(selectedUserId)
                } else showMessage("Failed to reset credits")
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
            loadUserDataById(selectedUserId)
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
        creditManager.setAdminMode(false)
        FirebaseAuth.getInstance().signOut()
        showMessage("Admin logged out")

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
