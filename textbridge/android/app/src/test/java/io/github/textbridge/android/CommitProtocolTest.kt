package io.github.textbridge.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitProtocolTest {
    @Test
    fun normalizeCommitUrlAddsSchemeAndPath() {
        assertEquals("http://192.168.1.10:17321/v1/commit", CommitProtocol.normalizeCommitUrl("192.168.1.10:17321"))
    }

    @Test
    fun normalizeCommitUrlPreservesExplicitScheme() {
        assertEquals("https://example.test/v1/commit", CommitProtocol.normalizeCommitUrl("https://example.test/"))
    }

    @Test
    fun normalizeKeyUrlAddsKeyPath() {
        assertEquals("http://127.0.0.1:17321/v1/key", CommitProtocol.normalizeKeyUrl("127.0.0.1:17321"))
    }

    @Test
    fun keyRequestBodyUsesWireShape() {
        val json = JSONObject(
            String(
                CommitProtocol.keyRequestBody(
                    requestId = "request-id",
                    key = RemoteKey.RETURN,
                    modifiers = listOf(KeyModifier.CONTROL),
                ),
            ),
        )

        assertEquals("request-id", json.getString("id"))
        assertEquals("Return", json.getString("key"))
        assertEquals("Control", json.getJSONArray("modifiers").getString(0))
    }

    @Test
    fun parseOkResponse() {
        val result = CommitProtocol.parseResult(200, """{"status":"ok"}""")

        assertTrue(result.ok)
        assertEquals("已发送", result.message)
    }

    @Test
    fun parseOkKeyResponseUsesCustomSuccessMessage() {
        val result = CommitProtocol.parseResult(200, """{"status":"ok"}""", successMessage = "按键已发送")

        assertTrue(result.ok)
        assertEquals("按键已发送", result.message)
    }

    @Test
    fun parseKnownFailureStatus() {
        val result = CommitProtocol.parseResult(503, """{"status":"no_focused_input"}""")

        assertFalse(result.ok)
        assertEquals("电脑没有输入焦点", result.message)
    }

    @Test
    fun parseInvalidKeyFailure() {
        val result = CommitProtocol.parseResult(400, """{"status":"invalid_key"}""")

        assertFalse(result.ok)
        assertEquals("按键无效", result.message)
    }

    @Test
    fun parseUnknownFailureIncludesHttpCode() {
        val result = CommitProtocol.parseResult(418, """{"status":"unexpected"}""")

        assertFalse(result.ok)
        assertEquals("发送失败 (418)", result.message)
    }
}
