package com.novastream.tv

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class RestrictedContentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_restricted", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    val hasPin: Boolean get() = prefs.contains("pin_hash") && prefs.contains("pin_salt")

    fun setPin(pin: String) {
        require(pin.length >= 4 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString("pin_hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return java.security.MessageDigest.isEqual(expected, derive(pin, salt))
    }

    fun isRestricted(item: PlaylistItem): Boolean =
        item.contentRating == ContentRating.MATURE || item.contentRating == ContentRating.UNRATED

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
}
