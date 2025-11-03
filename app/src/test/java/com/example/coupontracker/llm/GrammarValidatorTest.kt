package com.example.coupontracker.llm

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarValidatorTest {

    private val grammarFile = File("src/main/assets/coupon_schema.gbnf")

    @Test
    fun `validateOrThrow accepts trusted grammar`() {
        val grammarText = grammarFile.readText()
        val hash = sha256(grammarText.toByteArray())

        GrammarValidator.validateOrThrow(hash, grammarText)
    }

    @Test
    fun `validateOrThrow rejects malformed grammar`() {
        val result = runCatching {
            GrammarValidator.validateOrThrow("0000", "root ::= something")
        }

        val exception = result.exceptionOrNull()

        assertTrue("Expected validator to fail for untrusted hash", result.isFailure)
        assertTrue("Expected IllegalStateException", exception is IllegalStateException)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }
}

