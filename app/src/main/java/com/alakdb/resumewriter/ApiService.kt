package com.alakdb.resumewriter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException

class ApiService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // This will log all requests/responses
        })
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"

    // Request data classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(
        val resume_text: String,
        val job_description: String,
        val tone: String = "Professional"
    )

    // Result sealed class
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    suspend fun getCurrentUserToken(): String? {
        return try {
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.e("ApiService", "Error getting user token: ${e.message}")
            null
        }
    }

    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Connection failed: ${response.code} - ${response.message}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Connection test failed: ${e.message}")
        }
    }

    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated")
            }

            val requestBody = gson.toJson(DeductCreditRequest(user_id = userId))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.getBoolean("success")) {
                    ApiResult.Success(jsonResponse)
                } else {
                    ApiResult.Error(jsonResponse.getString("message"))
                }
            } else {
                ApiResult.Error("Empty response from server")
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun generateResume(
        resumeText: String,
        jobDescription: String,
        tone: String = "Professional"
    ): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated")
            }

            val requestBody = gson.toJson(
                GenerateResumeRequest(resumeText, jobDescription, tone)
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server")
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    suspend fun generateResumeFromFiles(
        resumeFileUri: Uri,
        jobDescFileUri: Uri,
        tone: String = "Professional"
    ): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated")
            }

            // Convert URIs to files and create multipart request
            val resumeFile = uriToFile(resumeFileUri)
            val jobDescFile = uriToFile(jobDescFileUri)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart(
                    "resume_file", 
                    resumeFile.name ?: "resume.pdf",
                    resumeFile.asRequestBody(getMediaType(resumeFileUri))
                )
                .addFormDataPart(
                    "job_description_file", 
                    jobDescFile.name ?: "job_description.pdf",
                    jobDescFile.asRequestBody(getMediaType(jobDescFileUri))
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server")
            }
        } catch (e: Exception) {
            ApiResult.Error("File upload error: ${e.message}")
        }
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated")
            }

            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server")
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    // Helper functions for file handling
    private fun uriToFile(uri: Uri): File {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val file = File.createTempFile("upload_", "_temp", context.cacheDir)
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            throw IOException("Failed to convert URI to file: ${e.message}")
        }
    }

    private fun getMediaType(uri: Uri): MediaType? {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.toMediaType() ?: "application/octet-stream".toMediaType()
    }

    // Extension function for File to RequestBody
    private fun File.asRequestBody(mediaType: MediaType?): RequestBody {
        return this.inputStream().readBytes().toRequestBody(mediaType)
    }

    // Utility function to decode base64 file data
    fun decodeBase64File(base64Data: String): ByteArray {
        return android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
    }

    // Utility function to save file data to storage
    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }
}
