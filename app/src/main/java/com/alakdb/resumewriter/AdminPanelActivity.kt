package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
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

    private val usersList = mutableListOf<Pair<String, String>>() // Master list of users (docId, email)

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
        binding.btnAdminAdd3.setOnClickListener { modifyUserCredits(3, "add") }
        binding.btnAdminAdd5.setOnClickListener { modifyUserCredits(5, "add") }
        binding.btnAdminAdd8.setOnClickListener { modifyUserCredits(8, "add") }
        binding.btnAdminAdd20.setOnClickListener { modifyUserCredits(20, "add") }
        binding.btnAdminReset.setOnClickListener { modifyUserCredits(0, "reset") } 
        binding.btnAdminStats.setOnClickListener { showUserStats() }
        binding.btnAdminLogout.setOnClickListener { logoutAdmin() }
        binding.btnBlockUser.setOnClickListener { toggleUserBlockStatus() }

        binding.btnRefreshUsers.setOnClickListener {
            showMessage("Refreshing users from server...")
            loadUsers()
        }

        binding.spUserSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (position == 0) {
                    clearSelection()
                    Log.d("AdminPanel", "No user selected (spinner default)")
                    return
                }

                val adjustedPosition = position - 1
                if (adjustedPosition >= 0 && adjustedPosition < usersList.size) {
                    val selectedPair = usersList[adjustedPosition]
                    val userId = selectedPair.first
                    val userEmail = selectedPair.second

                    validateUserExists(userId) { isValid ->
                        if (isValid) {
                            selectUser(userId, userEmail)
                        } else {
                            showMessage("User no longer exists - refreshing list")
                            clearSelection()
                            loadUsers()
                        }
                    }
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
        db.collection("users").get(Source.SERVER)
            .addOnSuccessListener { documents ->
                usersList.clear()
                val defaultOption = "" to "Select a user..."
                for (doc in documents) {
                    val email = doc.getString("email") ?: "Unknown Email"
                    usersList.add(doc.id to email)
                }
                val displayList = mutableListOf<String>()
                displayList.add(defaultOption.second)
                displayList.addAll(usersList.map { it.second })
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spUserSelector.adapter = adapter

                if (usersList.isEmpty()) showMessage("No users found in database")
            }
            .addOnFailureListener { e ->
                Log.e("AdminPanel", "Firestore error: ${e.message}", e)
                showMessage("Failed to load users: ${e.message}")
                binding.tvUserStats.text = "Users: Failed to load"
            }
    }

    private fun validateUserExists(userId: String, callback: (Boolean) -> Unit) {
        if (userId.isEmpty()) {
            callback(false)
            return
        }

        db.collection("users").document(userId).get(Source.SERVER)
            .addOnSuccessListener { document -> callback(document.exists()) }
            .addOnFailureListener { callback(false) }
    }

    private fun loadAdminStats() {
    binding.tvUserStats.text = "Users: Loading..."
    binding.tvCreditStats.text = "Credits: Loading..."
    binding.tvCvStats.text = "CVs Generated: Loading..."

    db.collection("users").get()
        .addOnSuccessListener { documents ->
            try {
                val totalUsers = documents.size()
                var totalCredits = 0L
                var totalCVs = 0L
                var blockedUsers = 0L
                val dailyCount = mutableMapOf<String, Int>()
                val monthlyCount = mutableMapOf<String, Int>()
                val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

                for (doc in documents) {
                    try {
                        // Safe null handling for all fields
                        totalCredits += doc.getLong("totalCreditsEarned") ?: 0
                        totalCVs += doc.getLong("usedCredits") ?: 0
                        
                        val isBlocked = doc.getBoolean("isBlocked") 
                        if (isBlocked == true) blockedUsers++
                        
                        val createdAt = doc.getLong("createdAt")
                        if (createdAt != null && createdAt > 0) {
                            try {
                                val date = Date(createdAt)
                                val dayKey = dayFormat.format(date)
                                val monthKey = monthFormat.format(date)
                                
                                dailyCount[dayKey] = dailyCount.getOrDefault(dayKey, 0) + 1
                                monthlyCount[monthKey] = monthlyCount.getOrDefault(monthKey, 0) + 1
                            } catch (e: Exception) {
                                Log.e("AdminPanel", "Error parsing date for user ${doc.id}", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AdminPanel", "Error processing user ${doc.id}: ${e.message}")
                        // Continue with next document instead of crashing
                    }
                }

                val today = dayFormat.format(Date())
                val thisMonth = monthFormat.format(Date())

                // Safe UI updates on main thread
                runOnUiThread {
                    binding.tvUserStats.text = """
                        Total Users: $totalUsers
                        Blocked Users: $blockedUsers
                        Joined Today: ${dailyCount.getOrDefault(today, 0)}
                        Joined This Month: ${monthlyCount.getOrDefault(thisMonth, 0)}
                    """.trimIndent()
                    
                    binding.tvCreditStats.text = "Total Credits Earned: $totalCredits"
                    binding.tvCvStats.text = "Total CVs Generated: $totalCVs"
                }
                
            } catch (e: Exception) {
                Log.e("AdminPanel", "Error in loadAdminStats: ${e.message}", e)
                runOnUiThread {
                    showMessage("Error loading stats: ${e.message}")
                    binding.tvUserStats.text = "Users: Error"
                    binding.tvCreditStats.text = "Credits: Error"
                    binding.tvCvStats.text = "CVs: Error"
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("AdminPanel", "Failed to load admin stats: ${e.message}", e)
            runOnUiThread {
                showMessage("Failed to load admin stats: ${e.message}")
                binding.tvUserStats.text = "Users: Failed to load"
                binding.tvCreditStats.text = "Credits: Failed to load"
                binding.tvCvStats.text = "CVs Generated: Failed to load"
            }
        }
}

    private fun setupManualEmailLoad() {
        binding.btnLoadUser.setOnClickListener {
            val emailInput = binding.etManualEmail.text.toString().trim()
            if (emailInput.isEmpty()) {
                showMessage("Enter an email first")
                return@setOnClickListener
            }

            val match = usersList.find { it.second.equals(emailInput, ignoreCase = true) }
            if (match != null) {
                validateUserExists(match.first) { isValid ->
                    if (isValid) {
                        selectUser(match.first, match.second)
                        val index = usersList.indexOf(match) + 1
                        if (index < binding.spUserSelector.count) binding.spUserSelector.setSelection(index)
                    } else {
                        showMessage("User was deleted - refreshing list")
                        loadUsers()
                    }
                }
            } else {
                db.collection("users").whereEqualTo("email", emailInput).get(Source.SERVER)
                    .addOnSuccessListener { documents ->
                        when {
                            documents.isEmpty -> showMessage("User not found with email: $emailInput")
                            documents.size() > 1 -> handleManualUserLoad(documents.documents[0], emailInput)
                            else -> handleManualUserLoad(documents.documents[0], emailInput)
                        }
                    }
                    .addOnFailureListener { e -> showMessage("Failed to load user: ${e.message}") }
            }
        }
    }

    private fun handleManualUserLoad(document: DocumentSnapshot, emailInput: String) {
        val userId = document.id
        val userEmail = document.getString("email") ?: emailInput

        if (usersList.none { it.first == userId }) {
            usersList.add(userId to userEmail)
            refreshSpinnerAdapter()
        }

        selectUser(userId, userEmail)

        val spinnerIndex = usersList.indexOfFirst { it.first == userId } + 1
        if (spinnerIndex > 0 && spinnerIndex < binding.spUserSelector.count) binding.spUserSelector.setSelection(spinnerIndex)
    }

    private fun refreshSpinnerAdapter() {
        val displayList = mutableListOf("Select a user...") + usersList.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spUserSelector.adapter = adapter
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
        updateUserDisplay(0, 0, 0, false)
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
                val emailVerified = doc.getBoolean("emailVerified") ?: false
                
                isUserBlocked = blocked
                updateUserDisplay(available, used, total, emailVerified)
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
            .setPositiveButton("Yes") { _, _ ->
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
            binding.btnBlockUser.setBackgroundColor(resources.getColor(R.color.colorUnblockGreen, null))
        } else {
            binding.btnBlockUser.text = "Block User"
            binding.btnBlockUser.setBackgroundColor(resources.getColor(R.color.colorBlockRed, null))
        }
    
        // Enable/disable other buttons based on block status
        val isEnabled = !isBlocked
        binding.btnAdminAdd3.isEnabled = isEnabled
        binding.btnAdminAdd5.isEnabled = isEnabled
        binding.btnAdminAdd8.isEnabled = isEnabled
        binding.btnAdminAdd20.isEnabled = isEnabled
        binding.btnAdminReset.isEnabled = isEnabled
    
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
                    
                    // Record credit award history
                    recordCreditAward(amount, "Admin added credits")
                } else showMessage("Failed to add credits")
            }
        }
    }

    private fun recordCreditAward(amount: Int, reason: String) {
        if (selectedUserId.isEmpty()) return
        
        val awardData = hashMapOf(
            "userId" to selectedUserId,
            "amount" to amount,
            "reason" to reason,
            "timestamp" to System.currentTimeMillis(),
            "adminAction" to true
        )
        
        db.collection("creditAwards")
            .add(awardData)
            .addOnSuccessListener {
                Log.d("AdminPanel", "Credit award recorded: $reason - $amount credits")
            }
            .addOnFailureListener { e ->
                Log.e("AdminPanel", "Failed to record credit award", e)
            }
    }

    private fun showUserStats() {
        if (selectedUserId.isEmpty()) {
            showMessage("Please select a user first")
            return
        }

        // First get user basic info
        db.collection("users").document(selectedUserId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    // Then get credit award history
                    db.collection("creditAwards")
                        .whereEqualTo("userId", selectedUserId)
                        .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(10) // Show last 10 awards
                        .get()
                        .addOnSuccessListener { awardDocuments ->
                            val blockedStatus = if (doc.getBoolean("isBlocked") == true) "Yes" else "No"
                            val emailVerified = if (doc.getBoolean("emailVerified") == true) "Yes" else "No"
                            
                            // Build credit history string
                            val creditHistory = StringBuilder()
                            if (awardDocuments.isEmpty) {
                                creditHistory.append("No credit award history found")
                            } else {
                                creditHistory.append("Recent Credit Awards:\n")
                                val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                                
                                for (awardDoc in awardDocuments) {
                                    val amount = awardDoc.getLong("amount") ?: 0
                                    val reason = awardDoc.getString("reason") ?: "Unknown"
                                    val timestamp = awardDoc.getLong("timestamp") ?: 0
                                    val date = if (timestamp > 0) dateFormat.format(java.util.Date(timestamp)) else "Unknown date"
                                    
                                    creditHistory.append("• $date: $amount credits - $reason\n")
                                }
                            }
                            
                            val stats = """
                                Email: ${doc.getString("email")}
                                Email Verified: $emailVerified
                                Available Credits: ${doc.getLong("availableCredits") ?: 0}
                                Used Credits: ${doc.getLong("usedCredits") ?: 0}
                                Total Credits: ${doc.getLong("totalCreditsEarned") ?: 0}
                                Blocked: $blockedStatus
                                Created: ${doc.getLong("createdAt")?.let { 
                                    java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(it) } ?: "Unknown"}
                                
                                $creditHistory
                            """.trimIndent()
                            
                            AlertDialog.Builder(this)
                                .setTitle("User Statistics")
                                .setMessage(stats)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        .addOnFailureListener { e ->
                            showMessage("Failed to load credit history: ${e.message}")
                        }
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

    private fun updateUserDisplay(available: Int, used: Int, total: Int, emailVerified: Boolean) {
        binding.tvUserEmail.text = "User: $selectedUserEmail ${if (isUserBlocked) "(BLOCKED)" else ""} ${if (emailVerified) "(Verified)" else "(Not Verified)"}"
        binding.tvAvailableCredits.text = "Available: $available"
        binding.tvUsedCredits.text = "Used: $used"
        binding.tvTotalCredits.text = "Total: $total"
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d("AdminPanel", message)
    }
}
