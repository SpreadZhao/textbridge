package io.github.textbridge.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCommitProtocolTest {
    @Test
    fun commitRequestBodyUsesBluetoothWireShape() {
        val json = JSONObject(
            String(
                BluetoothCommitProtocol.commitRequestBody(
                    token = "secret-token",
                    requestId = "request-id",
                    text = "hello",
                ),
            ),
        )

        assertEquals(1, json.getInt("v"))
        assertEquals("request-id", json.getString("id"))
        assertEquals("secret-token", json.getString("token"))
        assertEquals("commit", json.getString("action"))
        assertEquals("hello", json.getString("text"))
    }

    @Test
    fun keyRequestBodyUsesBluetoothWireShape() {
        val json = JSONObject(
            String(
                BluetoothCommitProtocol.keyRequestBody(
                    token = "secret-token",
                    requestId = "request-id",
                    key = RemoteKey.RETURN,
                    modifiers = listOf(KeyModifier.CONTROL),
                ),
            ),
        )

        assertEquals(1, json.getInt("v"))
        assertEquals("key", json.getString("action"))
        assertEquals("Return", json.getString("key"))
        assertEquals("Control", json.getJSONArray("modifiers").getString(0))
    }

    @Test
    fun parseBluetoothOkResponse() {
        val result = BluetoothCommitProtocol.parseResult("""{"status":"ok"}""", successMessage = "按键已发送")

        assertTrue(result.ok)
        assertEquals("按键已发送", result.message)
    }

    @Test
    fun parseBluetoothKnownFailure() {
        val result = BluetoothCommitProtocol.parseResult("""{"status":"no_focused_input"}""")

        assertFalse(result.ok)
        assertEquals("电脑没有输入焦点", result.message)
    }
}
