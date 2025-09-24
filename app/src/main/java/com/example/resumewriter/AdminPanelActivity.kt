package com.example.resumewriter

class AdminPanelActivity : AppCompatActivity() {
    private lateinit var creditManager: CreditManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_panel)
        
        creditManager = CreditManager(this)
        
        if (!creditManager.isAdminMode()) {
            showMessage("Admin access required!")
            finish()
            return
        }
        
        setupAdminControls()
        updateAdminDisplay()
    }
    
    private fun setupAdminControls() {
        val btnAdd10 = findViewById<Button>(R.id.btn_admin_add_10)
        val btnAdd50 = findViewById<Button>(R.id.btn_admin_add_50)
        val btnSet100 = findViewById<Button>(R.id.btn_admin_set_100)
        val btnReset = findViewById<Button>(R.id.btn_admin_reset)
        val btnGenerateFree = findViewById<Button>(R.id.btn_admin_generate_free)
        val btnUserStats = findViewById<Button>(R.id.btn_admin_stats)
        val btnLogout = findViewById<Button>(R.id.btn_admin_logout)
        
        btnAdd10.setOnClickListener { 
            creditManager.adminAddCredits(10)
            updateAdminDisplay()
            showMessage("Added 10 credits!")
        }
        
        btnAdd50.setOnClickListener { 
            creditManager.adminAddCredits(50)
            updateAdminDisplay()
            showMessage("Added 50 credits!")
        }
        
        btnSet100.setOnClickListener { 
            creditManager.adminSetCredits(100)
            updateAdminDisplay()
            showMessage("Set credits to 100!")
        }
        
        btnReset.setOnClickListener { 
            creditManager.adminResetCredits()
            updateAdminDisplay()
            showMessage("Credits reset!")
        }
        
        btnGenerateFree.setOnClickListener { 
            if (creditManager.adminGenerateCV()) {
                showMessage("CV generated (admin mode - no credits used)!")
                updateAdminDisplay()
            }
        }
        
        btnUserStats.setOnClickListener {
            val stats = creditManager.adminGetUserStats()
            AlertDialog.Builder(this)
                .setTitle("User Statistics")
                .setMessage(stats)
                .setPositiveButton("OK", null)
                .show()
        }
        
        btnLogout.setOnClickListener {
            creditManager.logoutAdmin()
            showMessage("Admin logged out!")
            finish()
        }
    }
    
    private fun updateAdminDisplay() {
        val tvAdminStats = findViewById<TextView>(R.id.tv_admin_stats)
        val stats = creditManager.adminGetUserStats()
        tvAdminStats.text = stats
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
