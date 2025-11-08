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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

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

    // ⭐⭐⭐ ADD THIS METHOD TO AdminPanelActivity ⭐⭐⭐
private fun refreshVerificationStatus() {
    if (selectedUserId.isEmpty()) {
        showMessage("Please select a user first")
        return
    }

    showMessage("🔄 Checking verification status...")
    
    val user = FirebaseAuth.getInstance().currentUser
    user?.reload()?.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val isVerified = user.isEmailVerified
            
            // Update Firestore
            db.collection("users").document(selectedUserId)
                .update("emailVerified", isVerified, "lastUpdated", System.currentTimeMillis())
                .addOnSuccessListener {
                    showMessage("✅ Verification status updated: ${if (isVerified) "Verified" else "Not Verified"}")
                    loadUserDataById(selectedUserId) // Refresh display
                }
                .addOnFailureListener { e ->
                    showMessage("❌ Failed to update verification status")
                }
        } else {
            showMessage("❌ Failed to check verification status")
        }
    }
}

// ⭐⭐⭐ ADD THIS METHOD FOR TOP CV GENERATORS ⭐⭐⭐
private fun showTopCVGenerators() {
    showMessage("📊 Loading top CV generators...")

    db.collection("users")
        .whereGreaterThan("usedCredits", 0) // Use usedCredits as CV count
        .orderBy("usedCredits", com.google.firebase.firestore.Query.Direction.DESCENDING)
        .limit(10)
        .get()
        .addOnSuccessListener { documents ->
            if (documents.isEmpty) {
                showMessage("No CV generation data found")
                return@addOnSuccessListener
            }

            val topUsers = StringBuilder()
            topUsers.append("🏆 TOP 10 CV GENERATORS\n")
            topUsers.append("(Based on credits used)\n\n")
            
            var rank = 1
            for (doc in documents) {
                val email = doc.getString("email") ?: "Unknown"
                val cvsGenerated = doc.getLong("usedCredits") ?: 0 // Use usedCredits
                val isVerified = doc.getBoolean("emailVerified") ?: false
                val isBlocked = doc.getBoolean("isBlocked") ?: false
                val availableCredits = doc.getLong("availableCredits") ?: 0
                
                topUsers.append("$rank. $email\n")
                topUsers.append("   📊 CVs: $cvsGenerated | ")
                topUsers.append("💰 Available: $availableCredits | ")
                topUsers.append("${if (isVerified) "✅" else "❌"}\n")
                
                if (rank == 1) {
                    topUsers.append("   👑 TOP PERFORMER!\n")
                }
                
                topUsers.append("\n")
                rank++
            }

            AlertDialog.Builder(this)
                .setTitle("Top CV Generators")
                .setMessage(topUsers.toString())
                .setPositiveButton("OK", null)
                .show()
        }
        .addOnFailureListener { e ->
            showMessage("Failed to load top CV generators: ${e.message}")
        }
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
        binding.btnCheckMultiAccounts.setOnClickListener { checkForMultiAccounts() }
        binding.btnTopCVGenerators.setOnClickListener { showTopCVGenerators() }
        binding.btnRefreshVerification.setOnClickListener { refreshVerificationStatus() }

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
                // ⭐⭐⭐ ADD NULL CHECK HERE ⭐⭐⭐
                if (documents == null) {
                    Log.e("AdminPanel", "Documents is null")
                    runOnUiThread {
                        binding.tvUserStats.text = "Users: No data"
                        binding.tvCreditStats.text = "Credits: No data"
                        binding.tvCvStats.text = "CVs: No data"
                    }
                    return@addOnSuccessListener
                }

                val totalUsers = documents.size()
                var totalCredits = 0L
                var totalCVs = 0L
                var blockedUsers = 0L
                var verifiedUsers = 0L
                var activeUsers = 0L

                for (doc in documents) {
                    try {
                        totalCredits += doc.getLong("totalCreditsEarned") ?: 0
                        totalCVs += doc.getLong("usedCredits") ?: 0
                        
                        val isBlocked = doc.getBoolean("isBlocked") ?: false
                        if (isBlocked) {
                            blockedUsers++
                        } else {
                            activeUsers++
                        }
                        
                        val isVerified = doc.getBoolean("emailVerified") ?: false
                        if (isVerified) verifiedUsers++
                        
                    } catch (e: Exception) {
                        Log.e("AdminPanel", "Error processing user ${doc.id}", e)
                    }
                }

                runOnUiThread {
                    binding.tvUserStats.text = """
                        Total Users: $totalUsers
                        ✅ Verified: $verifiedUsers
                        ❌ Not Verified: ${totalUsers - verifiedUsers}
                        🟢 Active: $activeUsers
                        🚫 Blocked: $blockedUsers
                    """.trimIndent()
                    
                    binding.tvCreditStats.text = "Total Credits: $totalCredits"
                    binding.tvCvStats.text = "Total CVs Generated: $totalCVs"
                }
                
            } catch (e: Exception) {
                Log.e("AdminPanel", "Error in loadAdminStats", e)
                runOnUiThread {
                    binding.tvUserStats.text = "Users: Error"
                    binding.tvCreditStats.text = "Credits: Error"
                    binding.tvCvStats.text = "CVs: Error"
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("AdminPanel", "Failed to load stats", e)
            runOnUiThread {
                binding.tvUserStats.text = "Users: Failed"
                binding.tvCreditStats.text = "Credits: Failed"
                binding.tvCvStats.text = "CVs: Failed"
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

    // ⭐⭐⭐ TEMPORARY DEBUG METHOD - CALL THIS TO TEST ⭐⭐⭐
private fun debugCheckCreditAwardsCollection() {
    showMessage("🔍 Debug: Checking creditAwards collection...")
    
    db.collection("creditAwards")
        .limit(5)
        .get()
        .addOnSuccessListener { allDocuments ->
            Log.d("CreditAwardsDebug", "=== ALL CREDIT AWARDS IN COLLECTION ===")
            allDocuments.forEach { doc ->
                Log.d("CreditAwardsDebug", "Doc ID: ${doc.id}")
                doc.data.forEach { (key, value) ->
                    Log.d("CreditAwardsDebug", "  $key: $value")
                }
                Log.d("CreditAwardsDebug", "---")
            }
            showMessage("Check Logcat for credit awards debug info")
        }
        .addOnFailureListener { e ->
            Log.e("CreditAwardsDebug", "Failed to read creditAwards collection: ${e.message}")
        }
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
        
        "reset" -> resetUserCreditsToZero() // ← ADD THIS CASE
    }
}

// ADD THIS NEW METHOD FOR RESETTING CREDITS
private fun resetUserCreditsToZero() {
    AlertDialog.Builder(this)
        .setTitle("Reset Credits to Zero")
        .setMessage("Are you sure you want to reset $selectedUserEmail's credits to 0?\n\nThis will set available credits to 0 but preserve used credits history.")
        .setPositiveButton("Reset to 0") { _, _ ->
            performCreditReset()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ADD THIS METHOD TO PERFORM THE ACTUAL RESET
private fun performCreditReset() {
    val updates = hashMapOf<String, Any>(
        "availableCredits" to 0,
        "lastUpdated" to System.currentTimeMillis()
    )

    db.collection("users").document(selectedUserId)
        .update(updates)
        .addOnSuccessListener {
            showMessage("✅ Credits reset to 0 for $selectedUserEmail")
            loadUserDataById(selectedUserId) // Refresh the display
            loadAdminStats() // Refresh overall stats
            
            // Record this action in credit awards
            recordCreditAward(0, "Admin reset credits to zero")
            
            Log.d("AdminPanel", "Credits reset to 0 for user: $selectedUserEmail")
        }
        .addOnFailureListener { e ->
            showMessage("❌ Failed to reset credits: ${e.message}")
            Log.e("AdminPanel", "Error resetting credits", e)
        }
}

private fun fixMissingCreditAwardEmails() {
    showMessage("🔧 Fixing credit awards missing emails...")
    
    db.collection("creditAwards")
        .whereEqualTo("userEmail", null) // Find documents missing userEmail
        .get()
        .addOnSuccessListener { documents ->
            if (documents.isEmpty) {
                showMessage("✅ No credit awards missing emails")
                return@addOnSuccessListener
            }
            
            showMessage("📝 Found ${documents.size()} awards missing emails")
            
            val batch = db.batch()
            var fixedCount = 0
            
            for (doc in documents) {
                val userId = doc.getString("userId")
                if (!userId.isNullOrBlank()) {
                    // Get user email from users collection
                    db.collection("users").document(userId).get()
                        .addOnSuccessListener { userDoc ->
                            val userEmail = userDoc.getString("email") ?: "Unknown User"
                            
                            // Update this credit award document
                            batch.update(doc.reference, "userEmail", userEmail)
                            fixedCount++
                            
                            Log.d("CreditAwardsFix", "Fixed award for user: $userEmail")
                            
                            // Commit after processing all documents
                            if (fixedCount == documents.size()) {
                                batch.commit()
                                    .addOnSuccessListener {
                                        showMessage("✅ Fixed $fixedCount credit awards")
                                        // Refresh the display
                                        if (selectedUserId.isNotEmpty()) {
                                            showUserStats()
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        showMessage("❌ Failed to commit fixes: ${e.message}")
                                    }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("CreditAwardsFix", "Failed to get user data for $userId", e)
                            fixedCount++
                        }
                } else {
                    fixedCount++ // Skip if no userId
                }
            }
        }
        .addOnFailureListener { e ->
            showMessage("❌ Failed to scan credit awards: ${e.message}")
        }
}



    private fun recordCreditAward(amount: Int, reason: String) {
    if (selectedUserId.isEmpty() || selectedUserEmail.isEmpty()) {
        Log.e("CreditAward", "Cannot record award: missing user data")
        return
    }
    
    // ⭐⭐⭐ ENSURE ALL REQUIRED FIELDS ARE PRESENT:
    val awardData = hashMapOf(
        "userId" to selectedUserId,
        "userEmail" to selectedUserEmail, // ⭐⭐⭐ CRITICAL FIELD
        "amount" to amount,
        "reason" to reason,
        "timestamp" to System.currentTimeMillis(),
        "adminAction" to true,
        "adminEmail" to "admin@system.com", // Track which admin did it
        "userEmailLower" to selectedUserEmail.toLowerCase(Locale.ROOT) // For case-insensitive queries
    )
    
    db.collection("creditAwards")
        .add(awardData)
        .addOnSuccessListener {
            Log.d("AdminPanel", "✅ Credit award recorded for $selectedUserEmail: $reason")
        }
        .addOnFailureListener { e ->
            Log.e("AdminPanel", "❌ Failed to record credit award", e)
            // ⭐⭐⭐ FALLBACK: Try without userEmail if the above fails
            val fallbackData = hashMapOf(
                "userId" to selectedUserId,
                "amount" to amount,
                "reason" to reason,
                "timestamp" to System.currentTimeMillis(),
                "adminAction" to true
            )
            db.collection("creditAwards").add(fallbackData)
        }
}

    private fun showUserStats() {
    if (selectedUserId.isEmpty()) {
        showMessage("Please select a user first")
        return
    }

    db.collection("users").document(selectedUserId).get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) {
                showMessage("User document not found")
                return@addOnSuccessListener
            }

            // Safe data extraction
            val email = doc.getString("email") ?: "N/A"
            val isBlocked = doc.getBoolean("isBlocked") ?: false
            val isVerified = doc.getBoolean("emailVerified") ?: false
            val availableCredits = doc.getLong("availableCredits") ?: 0
            val usedCredits = doc.getLong("usedCredits") ?: 0
            val totalCredits = doc.getLong("totalCreditsEarned") ?: 0
            val cvsGenerated = doc.getLong("cvGenerated") ?: 0
            
            // ⭐⭐⭐ FIX: Get network information ⭐⭐⭐
            val ipAddress = doc.getString("ipAddress") ?: "Not available"
            val deviceId = doc.getString("deviceId") ?: "Not available"
            val lastLoginIp = doc.getString("lastLoginIp") ?: "Not available"
            
            // Safe date formatting
            val createdAt = doc.getLong("createdAt") ?: 0
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            val createdDate = if (createdAt > 0) dateFormat.format(Date(createdAt)) else "Unknown"

            val stats = """
                👤 USER INFORMATION:
                📧 Email: $email
                ✅ Email Verified: ${if (isVerified) "✅ YES" else "❌ NO"}
                
                💰 CREDITS:
                Available: $availableCredits
                Used: $usedCredits
                Total Earned: $totalCredits
                CVs Generated: $cvsGenerated
                
                🌐 NETWORK INFORMATION:
                Registration IP: $ipAddress
                Last Login IP: $lastLoginIp
                Device ID: ${deviceId.take(8)}...
                
                ⚠️ ACCOUNT STATUS:
                Blocked: ${if (isBlocked) "🚫 YES" else "✅ NO"}
                
                📅 ACCOUNT CREATED:
                $createdDate
            """.trimIndent()

            // Load credit awards history
            loadCreditAwardsHistory(stats)
        }
        .addOnFailureListener { e ->
            showMessage("Failed to load user stats: ${e.message}")
        }
}

// ⭐⭐⭐ ADD THIS METHOD TO LOAD CREDIT AWARDS ⭐⭐⭐
private fun loadCreditAwardsHistory(basicStats: String) {
    db.collection("creditAwards")
        .whereEqualTo("userId", selectedUserId)
        .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
        .limit(20)
        .get()
        .addOnSuccessListener { awardDocuments ->
            // Check if documents have email field
            if (awardDocuments.isEmpty) {
                showCompleteStatsDialog(basicStats, "No credit award history found")
                return@addOnSuccessListener
            }
            
            // Optional: Debug to see what fields exist
            awardDocuments.documents.firstOrNull()?.data?.keys?.forEach { field ->
                Log.d("CreditAwardsDebug", "Available field: $field")
            }
            
            val creditHistory = buildCreditHistoryString(awardDocuments)
            showCompleteStatsDialog(basicStats, creditHistory)
        }
        .addOnFailureListener { e ->
            Log.e("AdminPanel", "Error loading credit awards: ${e.message}")
            showCompleteStatsDialog(basicStats, "❌ Error loading credit history")
        }
}

// ⭐⭐⭐ ADD THIS METHOD ⭐⭐⭐
private fun buildCreditHistoryString(awardDocuments: com.google.firebase.firestore.QuerySnapshot): String {
    if (awardDocuments.isEmpty) {
        return "No credit award history found"
    }

    val history = StringBuilder("📊 CREDIT AWARD HISTORY:\n\n")
    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    
    var count = 0
    for (doc in awardDocuments) {
        count++
        val amount = doc.getLong("amount") ?: 0
        val reason = doc.getString("reason") ?: "Unknown reason"
        val userEmail = doc.getString("userEmail") ?: "Unknown User" // ⭐⭐⭐ ADD THIS
        val timestamp = doc.getLong("timestamp") ?: 0
        val adminAction = doc.getBoolean("adminAction") ?: false
        val date = if (timestamp > 0) dateFormat.format(Date(timestamp)) else "Unknown date"
        
        history.append("${count}. 💰 $amount credits\n")
        history.append("   👤 $userEmail\n") // ⭐⭐⭐ DISPLAY EMAIL
        history.append("   📝 $reason\n")
        history.append("   ${if (adminAction) "🛡️ Admin Action" else "🤖 System"}\n")
        history.append("   📅 $date\n")
        history.append("   ${"-".repeat(40)}\n")
    }
    
    history.append("\n📈 Total awards found: $count")
    return history.toString()
}

// ⭐⭐⭐ ADD THIS METHOD ⭐⭐⭐
private fun showCompleteStatsDialog(basicStats: String, creditHistory: String) {
    val fullStats = """
        $basicStats
        
        📊 CREDIT HISTORY:
        $creditHistory
    """.trimIndent()

    AlertDialog.Builder(this)
        .setTitle("User Statistics - $selectedUserEmail")
        .setMessage(fullStats)
        .setPositiveButton("OK", null)
        .setNeutralButton("Refresh") { _, _ -> showUserStats() }
        .show()
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
    private fun checkForMultiAccounts() {
    if (selectedUserId.isEmpty()) {
        showMessage("Please select a user first")
        return
    }

    showMessage("🔍 Checking for related accounts...")

    // Get the selected user's IP address
    db.collection("users").document(selectedUserId).get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) {
                showMessage("User not found")
                return@addOnSuccessListener
            }

            val userIP = doc.getString("ipAddress")
            val userEmail = doc.getString("email") ?: "Unknown"
            
            if (userIP.isNullOrEmpty()) {
                showMessage("No IP data available for this user")
                return@addOnSuccessListener
            }

            // Find other accounts with same IP
            db.collection("users")
                .whereEqualTo("ipAddress", userIP)
                .get()
                .addOnSuccessListener { documents ->
                    showAccountResults(documents, userIP, userEmail)
                }
                .addOnFailureListener { e ->
                    showMessage("Failed to check related accounts: ${e.message}")
                }
        }
        .addOnFailureListener { e ->
            showMessage("Failed to load user data: ${e.message}")
        }
}

private fun showAccountResults(documents: com.google.firebase.firestore.QuerySnapshot, ipAddress: String, currentUserEmail: String) {
    val message = StringBuilder()
    message.append("📊 Accounts from IP: $ipAddress\n\n")
    
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    var accountCount = 0
    documents.forEach { doc ->
        val email = doc.getString("email") ?: "Unknown"
        val isVerified = doc.getBoolean("emailVerified") ?: false
        val isBlocked = doc.getBoolean("isBlocked") ?: false
        val createdAt = doc.getLong("createdAt") ?: 0
        
        accountCount++
        
        message.append("$accountCount. $email\n")
        message.append("   ${if (isVerified) "✅ Verified" else "❌ Not Verified"} | ")
        message.append("${if (isBlocked) "🚫 Blocked" else "✅ Active"}\n")
        
        if (email == currentUserEmail) {
            message.append("   👈 Currently Selected\n")
        }
        message.append("   Created: ${dateFormat.format(Date(createdAt))}\n\n")
    }

    // Add summary
    message.append("=== SUMMARY ===\n")
    message.append("Total accounts found: $accountCount\n")
    
    when {
        accountCount >= 4 -> message.append("🚨 High chance of multi-account abuse")
        accountCount >= 3 -> message.append("⚠️  Suspicious - possible multi-accounts") 
        accountCount == 2 -> message.append("ℹ️  Could be normal (family/shared WiFi)")
        else -> message.append("✅ Normal single account")
    }

    AlertDialog.Builder(this)
        .setTitle("Related Accounts Report")
        .setMessage(message.toString())
        .setPositiveButton("OK", null)
        .show()
    }
}
