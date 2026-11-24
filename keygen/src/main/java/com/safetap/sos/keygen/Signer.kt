package com.safetap.sos.keygen

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Makes activation codes. Must stay in step with LicenseCrypto in the app module. */
object Signer {
    fun normalizeDevice(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    fun isValidDeviceCode(s: String) = normalizeDevice(s).matches(Regex("[0-9A-F]{8}"))

    fun isValidPrivateKey(b64: String): Boolean = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64.trim())))
        true
    } catch (e: Exception) {
        false
    }

    /** @return the activation code for [deviceCode], signed with the private key. */
    fun makeCode(privateKeyB64: String, deviceCode: String): String {
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyB64.trim())))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key)
        s.update(("PG1|" + normalizeDevice(deviceCode)).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign())
    }
}
