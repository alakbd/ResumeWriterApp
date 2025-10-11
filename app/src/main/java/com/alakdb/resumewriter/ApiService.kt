package com.alakdb.resumewriter

import android.util.Log
import android.content.Context
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com" // Replace with your API URL

    interface ApiCallback {
        fun onSuccess(response: JSONObject)
        fun onError(error: String)
    }

    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(
        val resume_text: String,
        val job_description: String,
        val tone: String = "Professional"
    )

    suspend fun getCurrentUserToken(): String? {
        return try {
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.e("ApiService", "Error getting user token: ${e.message}")
            null
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
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.getBoolean("success")) {
                    ApiResult.Success(jsonResponse)
                } else {
                    ApiResult.Error(jsonResponse.getString("message"))
                }
            } else {
                ApiResult.Error("Failed to deduct credit: ${response.message}")
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
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                ApiResult.Success(jsonResponse)
            } else {
                ApiResult.Error("Failed to generate resume: ${response.message}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
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
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Failed to get credits: ${response.message}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}")
        }
    }

    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }
}
