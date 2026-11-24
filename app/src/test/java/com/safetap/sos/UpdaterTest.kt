package com.safetap.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdaterTest {
    @Test fun newerVersionsDetected() {
        assertTrue(Updater.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("v2.0", "1.9.9"))
    }

    @Test fun sameOrOlderIgnored() {
        assertFalse(Updater.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("v0.9.9", "1.0.0"))
        assertFalse(Updater.isNewer("garbage", "1.0.0"))
    }

    @Test fun releaseNotesHaveNoLinksOrHostingText() {
        val body = "## What's Changed\nFaster trigger\nhttps://example.com/x\n**Full Changelog**: see link\nSee github for more"
        val clean = Updater.cleanNotes(body)
        assertTrue(clean.contains("Faster trigger"))
        assertFalse(clean.contains("http"))
        assertFalse(clean.lowercase().contains("github"))
        assertFalse(clean.lowercase().contains("changelog"))
    }
}

class LicenseCryptoTest {
    private fun sign(priv: java.security.PrivateKey, device: String): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(("PG1|" + LicenseCrypto.normalizeDevice(device)).toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign())
    }

    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(kp.public.encoded)

    @Test fun validCodeAccepted() {
        assertTrue(LicenseCrypto.verify(pub, "A1B2-C3D4", sign(kp.private, "A1B2-C3D4")))
    }

    @Test fun deviceCodeFormattingDoesNotMatter() {
        assertTrue(LicenseCrypto.verify(pub, "a1b2c3d4", sign(kp.private, "A1B2-C3D4")))
    }

    @Test fun codeForAnotherDeviceRejected() {
        assertFalse(LicenseCrypto.verify(pub, "FFFF-0000", sign(kp.private, "A1B2-C3D4")))
    }

    @Test fun codeFromDifferentKeyRejected() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertFalse(LicenseCrypto.verify(pub, "A1B2-C3D4", sign(other.private, "A1B2-C3D4")))
    }

    @Test fun garbageRejected() {
        assertFalse(LicenseCrypto.verify(pub, "A1B2-C3D4", "not a code"))
        assertFalse(LicenseCrypto.verify(pub, "A1B2-C3D4", ""))
    }

    @Test fun whitespaceInPastedCodeTolerated() {
        val c = sign(kp.private, "A1B2-C3D4")
        assertTrue(LicenseCrypto.verify(pub, "A1B2-C3D4", c.substring(0, 20) + "\n " + c.substring(20)))
    }
}
