package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import android.util.Log
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
    private var isUserBlocked: Boolean = false

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
        loadAdminStats()
    }

    private fun setupUI() {
        binding.btnAdminAdd10.setOnClickListener { modifyUserCredits(10, "add") }
        binding.btnAdminAdd50.setOnClickListener { modifyUserCredits(50, "add") }
        binding.btnAdminSet100.setOnClickListener { modifyUserCredits(100, "set") }
        binding.btnAdminReset.setOnClickListener { modifyUserCredits(0, "reset") }
        binding.btnAdminGenerateFree.setOnClickListener { generateFreeCV() }
        binding.btnAdminStats.setOnClickListener { showUserStats() }
        binding.btnAdminLogout.setOnClickListener { logoutAdmin() }
        binding.btnBlockUser.setOnClickListener { toggleUserBlockStatus() }

        binding.spUserSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (position == 0) {
                    clearSelection()
                    Log.d("AdminPanel", "No user selected (spinner default)")
                    return
                }

                // Fix: Check bounds before accessing
                if (position > 0 && position - 1 < usersList.size) {
                    val selectedPair = usersList[position - 1] // Adjust for "Select a user..." at index 0
                    val userId = selectedPair.first
                    val userEmail = selectedPair.second
                    Log.d("AdminPanel", "Spinner selected: $userEmail / $userId")
                    selectUser(userId, userEmail)
                } else {
                    clearSelection()
                    Log.e("AdminPanel", "Invalid spinner position: $position")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                clearSelection()
            }
        }
    }

    private fun loadUsers() {
        binding.tvUserStats.text = "Users: Loading..."
        
        db.collection("users").get()
            .addOnSuccessListener { documents ->
                usersList.clear()
                
                // Add default option first
                val defaultOption = "" to "Select a user..."
                
                for (doc in documents) {
                    val email = doc.getString("email") ?: "Unknown Email"
                    usersList.add(doc.id to email)
                }

                // Create adapter with default option + users
                val displayList = mutableListOf<String>()
                displayList.add(defaultOption.second)
                displayList.addAll(usersList.map { it.second })

                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    displayList
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spUserSelector.adapter = adapter
                
                if (usersList.isEmpty()) {
                    showMessage("No users found in database")
                }
            }
            .addOnFailureListener { e ->
                Log.e("AdminPanel", "Firestore error: ${e.message}", e)
                showMessage("Failed to load users: ${e.message}")
                binding.tvUserStats.text = "Users: Failed to load"
            }
    }

    private fun loadAdminStats() {
        binding.tvUserStats.text = "Users: Loading..."
        binding.tvCreditStats.text = "Credits: Loading..."
        binding.tvCvStats.text = "CVs Generated: Loading..."

        db.collection("users").get()
            .addOnSuccessListener { documents ->
                val totalUsers = documents.size()
                var totalCredits = 0L
                var totalCVs = 0L
                var blockedUsers = 0L
                val dailyCount = mutableMapOf<String, Int>()
                val monthlyCount = mutableMapOf<String, Int>()

                val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val monthFormat = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())

                for (doc in documents) {
                    totalCredits += doc.getLong("totalCreditsEarned") ?: 0
                    totalCVs += doc.getLong("usedCredits") ?: 0
                    
                    // Count blocked users
                    if (doc.getBoolean("isBlocked") == true) {
                        blockedUsers++
                    }

                    val createdAt = doc.getLong("createdAt")
                    if (createdAt != null) {
                        try {
                            val date = java.util.Date(createdAt)
                            val dayKey = dayFormat.format(date)
                            val monthKey = monthFormat.format(date)

                            dailyCount[dayKey] = dailyCount.getOrDefault(dayKey, 0) + 1
                            monthlyCount[monthKey] = monthlyCount.getOrDefault(monthKey, 0) + 1
                        } catch (e: Exception) {
                            Log.e("AdminPanel", "Error parsing date for user ${doc.id}", e)
                        }
                    }
                }

                val today = dayFormat.format(java.util.Date())
                val thisMonth = monthFormat.format(java.util.Date())

                binding.tvUserStats.text = """
                    Total Users: $totalUsers
                    Blocked Users: $blockedUsers
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
            .addOnFailureListener { e ->
                Log.e("AdminPanel", "Failed to load admin stats: ${e.message}", e)
                showMessage("Failed to load admin stats: ${e.message}")
                binding.tvUserStats.text = "Users: Error loading"
                binding.tvCreditStats.text = "Credits: Error loading"
                binding.tvCvStats.text = "CVs Generated: Error loading"
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
                // Update spinner selection (add 1 for the default option)
                val index = usersList.indexOf(match) + 1
                if (index < binding.spUserSelector.count) {
                    binding.spUserSelector.setSelection(index)
                }
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
                        val userEmail = doc.getString("email") ?: emailInput
                        
                        // Add to usersList and refresh spinner
                        usersList.add(userId to userEmail)
                        val displayList = mutableListOf<String>()
                        displayList.add("Select a user...")
                        displayList.addAll(usersList.map { it.second })
                        
                        val adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_item,
                            displayList
                        )
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        binding.spUserSelector.adapter = adapter

                        selectUser(userId, userEmail)
                        binding.spUserSelector.setSelection(usersList.size) // Last position
                    }
                    .addOnFailureListener { e -> 
                        showMessage("Failed to load user: ${e.message}")
                    }
            }
        }
    }

    private fun selectUser(userId: String, userEmail: String) {
        selectedUserId = userId
        selectedUserEmail = userEmail
        Log.d("AdminPanel", "Selected user: $selectedUserEmail / $selectedUserId")
        loadUserDataById(userId)
        binding.etManualEmail.setText(userEmail)
    }

    private fun clearSelection() {
        selectedUserId = ""
        selectedUserEmail = ""
        isUserBlocked = false
        binding.etManualEmail.setText("")
        updateUserDisplay(0, 0, 0)
        binding.tvUserEmail.text = "User: Not selected"
        updateBlockButtonUI(false)
    }

    private fun loadUserDataById(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    showMessage("User document not found")
                    clearSelection()
                    return@addOnSuccessListener
                }
                val available = doc.getLong("availableCredits")?.toInt() ?: 0
                val used = doc.getLong("usedCredits")?.toInt() ?: 0
                val total = doc.getLong("totalCreditsEarned")?.toInt() ?: 0
                val blocked = doc.getBoolean("isBlocked") ?: false
                
                isUserBlocked = blocked
                updateUserDisplay(available, used, total)
                updateBlockButtonUI(blocked)
            }
            .addOnFailureListener { e ->
                showMessage("Failed to load user data: ${e.message}")
                clearSelection()
            }
    }

    private fun toggleUserBlockStatus() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        val newBlockStatus = !isUserBlocked
        val action = if (newBlockStatus) "block" else "unblock"
        
        AlertDialog.Builder(this)
            .setTitle(if (newBlockStatus) "Block User" else "Unblock User")
            .setMessage("Are you sure you want to ${action} $selectedUserEmail?")
            .setPositiveButton("Yes") { dialog, which ->
                performBlockUser(newBlockStatus)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performBlockUser(block: Boolean) {
        val updates = hashMapOf<String, Any>(
            "isBlocked" to block,
            "lastUpdated" to System.currentTimeMillis()
        )

        db.collection("users").document(selectedUserId)
            .update(updates)
            .addOnSuccessListener {
                isUserBlocked = block
                updateBlockButtonUI(block)
                showMessage("User ${if (block) "blocked" else "unblocked"} successfully")
                loadAdminStats() // Refresh stats to update blocked user count
                
                // Log the action
                Log.d("AdminPanel", "User ${if (block) "blocked" else "unblocked"}: $selectedUserEmail")
            }
            .addOnFailureListener { e ->
                showMessage("Failed to ${if (block) "block" else "unblock"} user: ${e.message}")
                Log.e("AdminPanel", "Error updating block status", e)
            }
    }

    private fun updateBlockButtonUI(isBlocked: Boolean) {
        if (isBlocked) {
            binding.btnBlockUser.text = "Unblock User"
            binding.btnBlockUser.backgroundTint = getColorStateList(android.R.color.holo_green_light)
        } else {
            binding.btnBlockUser.text = "Block User"
            binding.btnBlockUser.backgroundTint = getColorStateList(android.R.color.holo_red_light)
        }
        
        // Enable/disable other buttons based on block status
        val isEnabled = !isBlocked
        binding.btnAdminAdd10.isEnabled = isEnabled
        binding.btnAdminAdd50.isEnabled = isEnabled
        binding.btnAdminSet100.isEnabled = isEnabled
        binding.btnAdminReset.isEnabled = isEnabled
        binding.btnAdminGenerateFree.isEnabled = isEnabled
        
        if (isBlocked) {
            showMessage("User is blocked - credit operations disabled")
        }
    }

    private fun modifyUserCredits(amount: Int, operation: String) {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        if (isUserBlocked) {
            showMessage("Cannot modify credits for blocked user")
            return
        }

        when (operation) {
            "add" -> creditManager.adminAddCreditsToUser(selectedUserId, amount) { success ->
                if (success) {
                    showMessage("Added $amount credits to $selectedUserEmail")
                    loadUserDataById(selectedUserId)
                    loadAdminStats()
                } else showMessage("Failed to add credits")
            }
            "set" -> creditManager.adminSetUserCredits(selectedUserId, amount) { success ->
                if (success) {
                    showMessage("Set credits to $amount for $selectedUserEmail")
                    loadUserDataById(selectedUserId)
                    loadAdminStats()
                } else showMessage("Failed to set credits")
            }
            "reset" -> creditManager.adminResetUserCredits(selectedUserId) { success ->
                if (success) {
                    showMessage("Reset credits for $selectedUserEmail")
                    loadUserDataById(selectedUserId)
                    loadAdminStats()
                } else showMessage("Failed to reset credits")
            }
        }
    }

    private fun generateFreeCV() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        if (isUserBlocked) {
            showMessage("Cannot generate CV for blocked user")
            return
        }

        db.collection("users").document(selectedUserId).update(
            "usedCredits", com.google.firebase.firestore.FieldValue.increment(1),
            "cvGenerated", com.google.firebase.firestore.FieldValue.increment(1),
            "lastUpdated", System.currentTimeMillis()
        ).addOnSuccessListener {
            showMessage("Free CV generated for $selectedUserEmail")
            loadUserDataById(selectedUserId)
            loadAdminStats()
        }.addOnFailureListener { e ->
            showMessage("Failed to generate CV: ${e.message}")
        }
    }

    private fun showUserStats() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        db.collection("users").document(selectedUserId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val blockedStatus = if (doc.getBoolean("isBlocked") == true) "Yes" else "No"
                    val stats = """
                        Email: ${doc.getString("email")}
                        Available Credits: ${doc.getLong("availableCredits") ?: 0}
                        Used Credits: ${doc.getLong("usedCredits") ?: 0}
                        Total Credits: ${doc.getLong("totalCreditsEarned") ?: 0}
                        Blocked: $blockedStatus
                        Created: ${doc.getLong("createdAt")?.let { 
                            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(it) } ?: "Unknown"}
                    """.trimIndent()
                    AlertDialog.Builder(this)
                        .setTitle("User Statistics")
                        .setMessage(stats)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showMessage("User document not found")
                }
            }
            .addOnFailureListener { e ->
                showMessage("Failed to load user stats: ${e.message}")
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
        binding.tvUserEmail.text = "User: $selectedUserEmail ${if (isUserBlocked) "(BLOCKED)" else ""}"
        binding.tvAvailableCredits.text = "Available: $available"
        binding.tvUsedCredits.text = "Used: $used"
        binding.tvTotalCredits.text = "Total: $total"
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d("AdminPanel", message)
    }
}
