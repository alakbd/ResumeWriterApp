package com.alakdb.resumewriter

import android.content.Intent
import android.util.Log
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ResumeGenerationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var creditManager: CreditManager
    private lateinit var auth: FirebaseAuth

     // ✅ Add this line to fix "Unresolved reference: baseUrl"
    private val baseUrl = "https://resume-writer-api.onrender.com"
    
    private var currentResumeText: String = ""
    private var currentJobDesc: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        apiService = ApiService(this)
        creditManager = CreditManager(this)
        auth = FirebaseAuth.getInstance()

        setupUI()
        loadUserData()
    }

    private fun setupUI() {
        binding.btnGenerateResume.setOnClickListener {
            generateResume()
        }

        binding.btnDownloadDocx.setOnClickListener {
            downloadFile("docx")
        }

        binding.btnDownloadPdf.setOnClickListener {
            downloadFile("pdf")
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadUserData() {
        // You can load any previously saved resume/job data here
        binding.etResumeText.setText(currentResumeText)
        binding.etJobDescription.setText(currentJobDesc)
    }

    private fun generateResume() {
        val resumeText = binding.etResumeText.text.toString().trim()
        val jobDesc = binding.etJobDescription.text.toString().trim()

        if (resumeText.isEmpty() || jobDesc.isEmpty()) {
            Toast.makeText(this, "Please enter both resume text and job description", Toast.LENGTH_SHORT).show()
            return
        }

        currentResumeText = resumeText
        currentJobDesc = jobDesc

        binding.btnGenerateResume.isEnabled = false
        binding.btnGenerateResume.text = "Processing..."

        CoroutineScope(Dispatchers.Main).launch {
            val user = auth.currentUser
            if (user == null) {
                showError("User not logged in")
                return@launch
            }

            // Step 1: Deduct credit
            when (val result = apiService.deductCredit(user.uid)) {
                is ApiService.ApiResult.Success -> {
                    // Step 2: Generate resume
                    when (val genResult = apiService.generateResume(resumeText, jobDesc)) {
                        is ApiService.ApiResult.Success -> {
                            val resumeData = genResult.data
                            displayGeneratedResume(resumeData)
                            showSuccess("Resume generated successfully!")
                        }
                        is ApiService.ApiResult.Error -> {
                            showError("Generation failed: ${genResult.message}")
                            // Refund credit if generation fails
                            creditManager.addCredits(1) {}
                        }
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showError("Credit deduction failed: ${result.message}")
                }
            }
            
            binding.btnGenerateResume.isEnabled = true
            binding.btnGenerateResume.text = "Generate Resume"
        }
    }

    private fun displayGeneratedResume(resumeData: JSONObject) {
        val resumeText = resumeData.getString("resume_text")
        val docxUrl = resumeData.getString("docx_url")
        val pdfUrl = resumeData.getString("pdf_url")
        
        // Store URLs for download
        binding.btnDownloadDocx.tag = docxUrl
        binding.btnDownloadPdf.tag = pdfUrl
        
        binding.tvGeneratedResume.text = resumeText
        binding.layoutDownloadButtons.visibility = android.view.View.VISIBLE
    }

    private fun downloadFile(format: String) {
        val url = when (format) {
            "docx" -> binding.btnDownloadDocx.tag as? String
            "pdf" -> binding.btnDownloadPdf.tag as? String
            else -> null
        }
        
        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$it"))
            startActivity(intent)
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e("ResumeGeneration", message) // ✅ Log import fixes this
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
