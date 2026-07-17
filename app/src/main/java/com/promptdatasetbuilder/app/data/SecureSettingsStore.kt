package com.promptdatasetbuilder.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val plain = appContext.getSharedPreferences("clean_app_settings", Context.MODE_PRIVATE)
    private val secure = appContext.getSharedPreferences("clean_secure_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        apiKey = decrypt(secure.getString(KEY_API_KEY, null)).orEmpty(),
        includeNsfw = plain.getBoolean(KEY_INCLUDE_NSFW, false),
        command = plain.getString(KEY_COMMAND, AppSettings.DEFAULT_COMMAND)
            ?.trim()
            ?.ifBlank { AppSettings.DEFAULT_COMMAND }
            ?: AppSettings.DEFAULT_COMMAND,
        pageSize = plain.getInt(KEY_PAGE_SIZE, 30).coerceIn(10, 100),
        ageConfirmed = plain.getBoolean(KEY_AGE_CONFIRMED, false),
    )

    fun save(settings: AppSettings) {
        plain.edit()
            .putBoolean(KEY_INCLUDE_NSFW, settings.includeNsfw)
            .putString(KEY_COMMAND, settings.command.trim().ifBlank { AppSettings.DEFAULT_COMMAND })
            .putInt(KEY_PAGE_SIZE, settings.pageSize.coerceIn(10, 100))
            .putBoolean(KEY_AGE_CONFIRMED, settings.ageConfirmed)
            .apply()

        secure.edit()
            .putString(KEY_API_KEY, encrypt(settings.apiKey.trim()))
            .apply()
    }

    private fun encrypt(value: String): String? {
        if (value.isBlank()) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val payload = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_INCLUDE_NSFW = "include_nsfw"
        const val KEY_COMMAND = "command"
        const val KEY_PAGE_SIZE = "page_size"
        const val KEY_AGE_CONFIRMED = "age_confirmed"

        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "prompt_dataset_clean_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
