package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityAdminPanelBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class AdminPanelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var creditManager: CreditManager
    private val db = Firebase.firestore
    private var selectedUserId: String = ""
    private var selectedUserEmail: String = ""

    // Master list of users (docId, email)
    private val usersList = mutableListOf<Pair<String, String>>()

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
        setupManualEmailLoad()
    }

    private fun setupUI() {
        binding.btnAdminAdd10.setOnClickListener { modifyUserCredits(10, "add") }
        binding.btnAdminAdd50.setOnClickListener { modifyUserCredits(50, "add") }
        binding.btnAdminSet100.setOnClickListener { modifyUserCredits(100, "set") }
        binding.btnAdminReset.setOnClickListener { modifyUserCredits(0, "reset") }
        binding.btnAdminGenerateFree.setOnClickListener { generateFreeCV() }
        binding.btnAdminStats.setOnClickListener { showUserStats() }
        binding.btnAdminLogout.setOnClickListener { logoutAdmin() }

        binding.spUserSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (position == 0) {
                    clearSelection()
                    return
                }

                val selectedPair = usersList[position]
                selectUser(selectedPair.first, selectedPair.second)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
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
            }
            .addOnFailureListener { showMessage("Failed to load users") }
    }

    private fun loadAdminStats() {
    db.collection("users").get()
        .addOnSuccessListener { documents ->
            val totalUsers = documents.size()
            var totalCredits = 0L
            var totalCVs = 0L
            val dailyCount = mutableMapOf<String, Int>()
            val monthlyCount = mutableMapOf<String, Int>()

            val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd")
            val monthFormat = java.text.SimpleDateFormat("yyyy-MM")

            for (doc in documents) {
                totalCredits += doc.getLong("totalCreditsEarned") ?: 0
                totalCVs += doc.getLong("usedCredits") ?: 0  // CVs generated counted as usedCredits

                val createdAt = doc.getLong("createdAt")
                if (createdAt != null) {
                    val date = java.util.Date(createdAt)
                    val dayKey = dayFormat.format(date)
                    val monthKey = monthFormat.format(date)

                    dailyCount[dayKey] = dailyCount.getOrDefault(dayKey, 0) + 1
                    monthlyCount[monthKey] = monthlyCount.getOrDefault(monthKey, 0) + 1
                }
            }

            val today = dayFormat.format(java.util.Date())
            val thisMonth = monthFormat.format(java.util.Date())

            binding.tvUserStats.text = """
                Total Users: $totalUsers
                Joined Today: ${dailyCount.getOrDefault(today, 0)}
                Joined This Month: ${monthlyCount.getOrDefault(thisMonth, 0)}
            """.trimIndent()

            binding.tvCreditStats.text = """
                Total Credits Earned: $totalCredits
            """.trimIndent()

            binding.tvCvStats.text = """
                Total CVs Generated: $totalCVs
            """.trimIndent()
        }
        .addOnFailureListener {
            showMessage("Failed to load admin stats")
        }
}



    private fun setupManualEmailLoad() {
        binding.btnLoadUser.setOnClickListener {
            val emailInput = binding.etManualEmail.text.toString().trim()
            if (emailInput.isEmpty()) {
                showMessage("Enter an email first")
                return@setOnClickListener
            }

            // Check if email exists in usersList first
            val match = usersList.find { it.second.equals(emailInput, ignoreCase = true) }
            if (match != null) {
                selectUser(match.first, match.second)
                // Update spinner selection
                val index = usersList.indexOf(match)
                binding.spUserSelector.setSelection(index)
            } else {
                // Fetch from Firestore if not in list
                db.collection("users").whereEqualTo("email", emailInput).get()
                    .addOnSuccessListener { documents ->
                        if (documents.isEmpty) {
                            showMessage("User not found")
                            return@addOnSuccessListener
                        }
                        val doc = documents.documents[0]
                        val userId = doc.id
                        val userEmail = doc.getString("email") ?: ""
                        // Add to usersList
                        usersList.add(userId to userEmail)
                        val adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_item,
                            usersList.map { it.second }
                        )
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        binding.spUserSelector.adapter = adapter

                        selectUser(userId, userEmail)
                        binding.spUserSelector.setSelection(usersList.size - 1)
                    }
                    .addOnFailureListener { showMessage("Failed to load user") }
            }
        }
    }

    private fun selectUser(userId: String, userEmail: String) {
        selectedUserId = userId
        selectedUserEmail = userEmail
        loadUserDataById(userId)
        binding.etManualEmail.setText(userEmail)
    }

    private fun clearSelection() {
        selectedUserId = ""
        selectedUserEmail = ""
        binding.etManualEmail.setText("")
        updateUserDisplay(0, 0, 0)
    }

    private fun loadUserDataById(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    showMessage("User not found")
                    return@addOnSuccessListener
                }
                val available = doc.getLong("availableCredits")?.toInt() ?: 0
                val used = doc.getLong("usedCredits")?.toInt() ?: 0
                val total = doc.getLong("totalCreditsEarned")?.toInt() ?: 0
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
                loadAdminStats()  // 🔹 refresh stats
            } else showMessage("Failed to add credits")
        }
        "set" -> creditManager.adminSetUserCredits(selectedUserId, amount) { success ->
            if (success) {
                showMessage("Set credits to $amount for $selectedUserEmail")
                loadUserDataById(selectedUserId)
                loadAdminStats()  // 🔹 refresh stats
            } else showMessage("Failed to set credits")
        }
        "reset" -> creditManager.adminResetUserCredits(selectedUserId) { success ->
            if (success) {
                showMessage("Reset credits for $selectedUserEmail")
                loadUserDataById(selectedUserId)
                loadAdminStats()  // 🔹 refresh stats
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
        "cvGenerated", com.google.firebase.firestore.FieldValue.increment(1),
        "lastUpdated", System.currentTimeMillis()
    ).addOnSuccessListener {
        showMessage("Free CV generated for $selectedUserEmail")
        loadUserDataById(selectedUserId)
        loadAdminStats()  // 🔹 refresh stats
    }.addOnFailureListener {
        showMessage("Failed to generate CV")
    }
}

// Also call after loading users on startup:
loadUsers()
loadAdminStats()

    private fun showUserStats() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        db.collection("users").document(selectedUserId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val stats = """
                        Email: ${doc.getString("email")}
                        Available Credits: ${doc.getLong("availableCredits") ?: 0}
                        Used Credits: ${doc.getLong("usedCredits") ?: 0}
                        Total Credits: ${doc.getLong("totalCreditsEarned") ?: 0}
                        Created: ${doc.getLong("createdAt")?.let { 
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
