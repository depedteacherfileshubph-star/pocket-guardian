package com.safetap.sos.keygen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SignerTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)

    /** Same check the app does (LicenseCrypto.verify), repeated here to prove the two sides agree. */
    private fun verify(device: String, code: String): Boolean {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(kp.public)
        s.update(("PG1|" + Signer.normalizeDevice(device)).toByteArray())
        return s.verify(Base64.getUrlDecoder().decode(code))
    }

    @Test fun codeVerifiesForSameDevice() {
        assertTrue(verify("A1B2-C3D4", Signer.makeCode(privB64, "a1b2-c3d4")))
    }

    @Test fun codeFailsForOtherDevice() {
        assertFalse(verify("0000-0000", Signer.makeCode(privB64, "A1B2-C3D4")))
    }

    @Test fun deviceCodeValidation() {
        assertTrue(Signer.isValidDeviceCode("A1B2-C3D4"))
        assertTrue(Signer.isValidDeviceCode("a1b2c3d4"))
        assertFalse(Signer.isValidDeviceCode("A1B2-C3"))
        assertFalse(Signer.isValidDeviceCode("ZZZZ-ZZZZ"))
    }

    @Test fun privateKeyValidation() {
        assertTrue(Signer.isValidPrivateKey(privB64))
        assertFalse(Signer.isValidPrivateKey("hello"))
        assertEquals(true, Signer.isValidPrivateKey(privB64 + "\n"))
    }
}
