package com.ethran.notable.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ethran.notable.BuildConfig

object ApiKeyManager {

    private const val ENCRYPTED_PREFS_FILE_NAME = "notable_secure_prefs"
    private const val API_KEY_ALIAS = "openai_api_key"

    private fun createEncryptedSharedPreferences(context: Context): EncryptedSharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val sharedPreferences = createEncryptedSharedPreferences(context)
        with(sharedPreferences.edit()) {
            putString(API_KEY_ALIAS, apiKey)
            apply()
        }
    }

    fun getApiKey(context: Context): String? {
        val sharedPreferences = createEncryptedSharedPreferences(context)
        var apiKey = sharedPreferences.getString(API_KEY_ALIAS, null)

        if (apiKey == null) {
            // Key not in encrypted prefs, try to get from BuildConfig and save it
            val buildConfigApiKey = BuildConfig.OPENAI_API_KEY
            if (buildConfigApiKey.isNotBlank() && buildConfigApiKey != "YOUR_OPENAI_API_KEY_HERE") {
                apiKey = buildConfigApiKey
                saveApiKey(context, apiKey)
            }
        }
        return apiKey
    }
} 