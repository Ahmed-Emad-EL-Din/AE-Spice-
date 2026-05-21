package com.example.engine

import android.content.Context
import android.content.SharedPreferences

class UserSessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_session_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_SIGNED_IN = "is_signed_in"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USE_CUSTOM_KEY = "use_custom_key"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_ACTIVE_MODEL = "active_model"
    }

    var isSignedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_SIGNED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_SIGNED_IN, value).apply()

    var userEmail: String
        get() = prefs.getString(KEY_USER_EMAIL, "ahmedconan1115@gmail.com") ?: "ahmedconan1115@gmail.com"
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Ahmed Conan") ?: "Ahmed Conan"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var useCustomKey: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_CUSTOM_KEY, value).apply()

    var customApiKey: String
        get() = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_API_KEY, value).apply()

    var activeModel: String
        get() = prefs.getString(KEY_ACTIVE_MODEL, "gemini-3.5-flash") ?: "gemini-3.5-flash"
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODEL, value).apply()

    fun getEffectiveApiKey(): String {
        return if (useCustomKey && customApiKey.isNotBlank()) {
            customApiKey.trim()
        } else {
            com.example.BuildConfig.GEMINI_API_KEY
        }
    }

    fun logout() {
        prefs.edit().apply {
            putBoolean(KEY_IS_SIGNED_IN, false)
            putString(KEY_USER_EMAIL, "ahmedconan1115@gmail.com")
            putString(KEY_USER_NAME, "Ahmed Conan")
            apply()
        }
    }
}
