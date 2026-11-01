package no.enaasen.tvremote.data.nowplaying

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone against a companion that really speaks TLS on the companion's port, including one
 * in the middle: a server with a certificate of its own that passes on the real answer.
 */
class CompanionClientTlsTest {
    private val secret = "00112233445566778899aabbccddeeff"
    private var server: FakeCompanion? = null

    @After
    fun stop() {
        server?.close()
    }

    // The tests ask with seek, a command that needs no JSON parsing (org.json isn't there in unit tests).
    private suspend fun ask(host: String) = CompanionClient.seek(host, secret, "com.plexapp.android", 1_000)

    @Test
    fun trustsACompanionThatProvesItHoldsTheSecretAndOnlyThenSendsIt() = runBlocking {
        server = FakeCompanion(certA, proofFor = certA)
        assertTrue(ask("127.0.0.1"))
        assertEquals(listOf("Authorization: Bearer $secret"), server!!.authorizations)
    }

    @Test
    fun refusesSomeoneInTheMiddleAndNeverSendsTheSecret() = runBlocking {
        // Their certificate is B, but what they relay from the real companion is its answer for A.
        server = FakeCompanion(certB, proofFor = certA)
        assertEquals(false, ask("localhost"))
        // Nothing is pinned, so the next try verifies again, and fails the same way.
        assertEquals(false, ask("localhost"))
        assertTrue("the secret was sent: ${server!!.authorizations}", server!!.authorizations.isEmpty())
    }

    @Test
    fun saysUnauthorizedWhenTheCompanionDoesNotKnowThePhone() = runBlocking {
        server = FakeCompanion(certA, proofFor = certA, knowsSecret = false)
        assertEquals(CompanionClient.Fetched.Unauthorized, CompanionClient.fetch("127.0.0.2", secret))
    }

    @Test
    fun verifiesAgainWhenTheCompanionsCertificateChanges() = runBlocking {
        // 127.0.0.3 reaches the same machine, and keeps this test's pin apart from the others.
        server = FakeCompanion(certA, proofFor = certA)
        assertTrue(ask("127.0.0.3"))
        server!!.close()

        // Reinstalled: a new certificate, and a proof that fits it.
        server = FakeCompanion(certB, proofFor = certB)
        // The pinned certificate no longer fits, which is told apart from a companion that isn't there ...
        assertEquals(CompanionClient.Fetched.CertificateChanged, CompanionClient.fetch("127.0.0.3", secret))
        // ... and the next verifies the new one.
        assertTrue(ask("127.0.0.3"))
    }

    @Test
    fun aMiddlemanCannotTakeOverAnAlreadyPinnedCompanion() = runBlocking {
        server = FakeCompanion(certA, proofFor = certA)
        assertTrue(ask("127.0.0.4"))
        server!!.close()

        server = FakeCompanion(certB, proofFor = certA)
        assertEquals(false, ask("127.0.0.4"))
        assertEquals(false, ask("127.0.0.4"))
        assertTrue("the secret was sent: ${server!!.authorizations}", server!!.authorizations.isEmpty())
    }

    /** A self-signed certificate with its key. */
    private class Identity(val keyStore: KeyStore, val certificate: X509Certificate) {
        val sha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    }

    private inner class FakeCompanion(
        identity: Identity,
        /** The certificate whose fingerprint goes into the answer: its own when honest, the real one's when relaying. */
        proofFor: Identity,
        private val knowsSecret: Boolean = true,
    ) {
        /** The Authorization headers of requests other than the challenge. */
        val authorizations = CopyOnWriteArrayList<String>()
        private val socket: ServerSocket
        private val proofSha256 = proofFor.sha256

        init {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(identity.keyStore, PASSWORD.toCharArray()) }.keyManagers
            val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
            socket = tls.serverSocketFactory.createServerSocket(CompanionClient.PORT, 8)
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        thread(isDaemon = true) { answer(client) }
                    } catch (_: Exception) {
                        // Closed.
                    }
                }
            }
        }

        private fun answer(client: java.net.Socket) = client.use {
            runCatching {
                it.soTimeout = 3_000
                val reader = it.getInputStream().bufferedReader()
                val path = reader.readLine().substringAfter(' ').substringBefore(' ')
                val headers = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                val authorization = headers.firstOrNull { line -> line.startsWith("Authorization:", ignoreCase = true) }
                val (status, body) = when {
                    path.startsWith("/challenge?") -> {
                        val nonce = path.substringAfter("nonce=").substringBefore('&')
                        if (knowsSecret) {
                            "200 OK" to CompanionChallenge.proof(secret, nonce, proofSha256).toHex()
                        } else {
                            "401 Unauthorized" to ""
                        }
                    }
                    else -> {
                        authorization?.let(authorizations::add)
                        "200 OK" to """{"sessions":[{"package":"com.plexapp.android","state":"playing","title":"Judgement Day"}]}"""
                    }
                }
                it.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    flush()
                }
            }
        }

        fun close() = socket.close()
    }

    private companion object {
        const val PASSWORD = "changeit"

        val certA: Identity by lazy { identity("a") }
        val certB: Identity by lazy { identity("b") }

        /** Makes a self-signed EC certificate with the JDK's keytool. */
        fun identity(name: String): Identity {
            val dir = Files.createTempDirectory("companion-tls").toFile()
            val file = File(dir, "$name.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val exit = ProcessBuilder(
                keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=$name", "-validity", "30", "-storetype", "PKCS12",
                "-keystore", file.path, "-storepass", PASSWORD, "-keypass", PASSWORD,
            ).redirectErrorStream(true).start().waitFor()
            check(exit == 0) { "keytool failed" }
            val keyStore = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
            dir.deleteOnExit()
            return Identity(keyStore, keyStore.getCertificate(name) as X509Certificate)
        }
    }
}
