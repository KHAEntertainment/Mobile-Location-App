package com.geoalign.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores user-entered paid-API keys (e.g. ipinfo Core) encrypted at rest (spec §8, §21). The
 * secret never appears in source or BuildConfig; it is encrypted with an AES-256 key held in the
 * hardware-backed AndroidKeyStore and the ciphertext lives in a private SharedPreferences file.
 */
interface SecureKeyStore {
    fun putApiKey(providerId: String, key: String)
    fun getApiKey(providerId: String): String?
    fun clearApiKey(providerId: String)
    fun hasApiKey(providerId: String): Boolean
}

class AndroidKeystoreSecureKeyStore(context: Context) : SecureKeyStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun putApiKey(providerId: String, key: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val ct = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        prefs.edit().putString(prefKey(providerId), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun getApiKey(providerId: String): String? {
        val stored = prefs.getString(prefKey(providerId), null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun clearApiKey(providerId: String) {
        prefs.edit().remove(prefKey(providerId)).apply()
    }

    override fun hasApiKey(providerId: String): Boolean = prefs.contains(prefKey(providerId))

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun prefKey(providerId: String) = "apikey_$providerId"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "geoalign_apikey_v1"
        private const val PREFS = "geoalign_secure"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
