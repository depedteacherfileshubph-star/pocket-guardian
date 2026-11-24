package com.safetap.sos

import android.content.Context
import android.provider.Settings
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Pure signature check, kept free of Android classes so it can be unit-tested. */
object LicenseCrypto {
    fun normalizeDevice(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** The activation code is an ECDSA signature over "PG1|<device code>", made with the seller's private key. */
    fun verify(publicKeyB64: String, deviceCode: String, code: String): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update(("PG1|" + normalizeDevice(deviceCode)).toByteArray(Charsets.UTF_8))
        sig.verify(Base64.getUrlDecoder().decode(code.filter { !it.isWhitespace() }))
    } catch (e: Exception) {
        false
    }
}

sealed interface LicenseState {
    data object Licensed : LicenseState
    data class Trial(val daysLeft: Int) : LicenseState
    data object Expired : LicenseState
}

/**
 * 7-day free trial, then a one-time purchase unlocks the guardian features on this phone.
 *
 * Safety rule: the child's on-screen SOS button and the volume shortcut with the settings that
 * were already saved NEVER stop working when the trial ends. Only guardian-side features lock
 * (changing settings and contacts, the screen-off shortcut, updates).
 */
object License {
    const val TRIAL_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    const val MESSENGER_URL = "https://m.me/RwinSoliman"

    /** Public half of the seller's licence key. Safe to publish: it can only check codes, not make them. */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0EDwPAe86k/QUA3ZjNfrvMWrNDkCN8Z/gnaUJTYmamfOOUJqZlkg8j2iRt2Xu3zLsna80OumiISEE1liB2HJCQ=="

    /** Short code the buyer sends with the payment proof. Derived from this phone; contains no personal data. */
    fun deviceCode(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val h = MessageDigest.getInstance("SHA-256").digest(("PG|$id").toByteArray())
        val hex = h.take(4).joinToString("") { "%02X".format(it) }
        return hex.substring(0, 4) + "-" + hex.substring(4)
    }

    fun state(ctx: Context, store: Store, now: Long = System.currentTimeMillis()): LicenseState {
        if (store.licenseCode.isNotEmpty() && LicenseCrypto.verify(PUBLIC_KEY, deviceCode(ctx), store.licenseCode)) {
            return LicenseState.Licensed
        }
        val t = maxOf(now, store.lastSeen) // a clock set backwards does not extend the trial
        store.lastSeen = t
        if (store.trialStart == 0L) store.trialStart = t
        val elapsed = t - store.trialStart
        if (elapsed >= TRIAL_DAYS * DAY_MS) return LicenseState.Expired
        val left = ((TRIAL_DAYS * DAY_MS - elapsed) + DAY_MS - 1) / DAY_MS
        return LicenseState.Trial(left.toInt().coerceIn(1, TRIAL_DAYS))
    }

    fun isExpired(ctx: Context, store: Store) = state(ctx, store) == LicenseState.Expired

    /** @return true and remembers the code if it is valid for this phone. */
    fun activate(ctx: Context, store: Store, code: String): Boolean {
        val clean = code.filter { !it.isWhitespace() }
        if (!LicenseCrypto.verify(PUBLIC_KEY, deviceCode(ctx), clean)) return false
        store.licenseCode = clean
        return true
    }
}
