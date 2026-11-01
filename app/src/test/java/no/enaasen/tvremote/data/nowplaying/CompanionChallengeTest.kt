package no.enaasen.tvremote.data.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionChallengeTest {
    private val secret = "00112233445566778899aabbccddeeff"
    private val nonce = "abcdef0123456789abcdef0123456789"
    private val certificate = ByteArray(32) { it.toByte() }

    // Made independently with Python's hmac and hashlib, and the companion app computes the same.
    private val expectedProof = "01c5c4cd2d376a4dd798940f9eb95f43fe8a96a204c467f3cd67b39dbc318a89"

    @Test
    fun proofMatchesTheKnownAnswer() {
        assertEquals(expectedProof, CompanionChallenge.proof(secret, nonce, certificate).toHex())
    }

    @Test
    fun keyIdNamesTheSecretWithoutGivingItAway() {
        assertEquals("5947d7c33d783f94", CompanionChallenge.keyId(secret))
    }

    @Test
    fun acceptsTheRightAnswerInAnyCase() {
        assertTrue(CompanionChallenge.accepts(secret, nonce, certificate, expectedProof))
        assertTrue(CompanionChallenge.accepts(secret, nonce, certificate, expectedProof.uppercase() + "\n"))
    }

    @Test
    fun rejectsAnAnswerForAnotherCertificate() {
        // Someone in the middle relays the real answer, but the phone sees their certificate.
        val theirs = ByteArray(32) { 0x7f }
        assertFalse(CompanionChallenge.accepts(secret, nonce, theirs, expectedProof))
    }

    @Test
    fun rejectsAnAnswerMadeWithAnotherSecretOrNonce() {
        assertFalse(CompanionChallenge.accepts("ffeeddccbbaa99887766554433221100", nonce, certificate, expectedProof))
        assertFalse(CompanionChallenge.accepts(secret, "00000000000000000000000000000000", certificate, expectedProof))
        assertFalse(CompanionChallenge.accepts(secret, nonce, certificate, ""))
    }
}
