package io.github.textbridge.android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface TextBridgeCommitClient {
    fun postCommit(address: String, token: String, requestId: String, text: String): SendResult
    fun postKey(
        address: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult

    fun postBluetoothCommit(deviceAddress: String, token: String, requestId: String, text: String): SendResult
    fun postBluetoothKey(
        deviceAddress: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult
}

class CommitClient(
    private val bluetoothClient: TextBridgeBluetoothCommitClient = UnavailableBluetoothCommitClient,
) : TextBridgeCommitClient {
    override fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        return postJson(
            url = CommitProtocol.normalizeCommitUrl(address),
            token = token,
            body = CommitProtocol.requestBody(requestId, text),
            successMessage = "已发送",
        )
    }

    override fun postKey(
        address: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult {
        return postJson(
            url = CommitProtocol.normalizeKeyUrl(address),
            token = token,
            body = CommitProtocol.keyRequestBody(requestId, key, modifiers),
            successMessage = "按键已发送",
        )
    }

    override fun postBluetoothCommit(
        deviceAddress: String,
        token: String,
        requestId: String,
        text: String,
    ): SendResult {
        return bluetoothClient.postCommit(
            deviceAddress = deviceAddress,
            token = token,
            requestId = requestId,
            text = text,
        )
    }

    override fun postBluetoothKey(
        deviceAddress: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult {
        return bluetoothClient.postKey(
            deviceAddress = deviceAddress,
            token = token,
            requestId = requestId,
            key = key,
            modifiers = modifiers,
        )
    }

    private fun postJson(url: String, token: String, body: ByteArray, successMessage: String): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }

            connection.outputStream.use { it.write(body) }
            CommitProtocol.parseResult(
                code = connection.responseCode,
                responseBody = readResponseBody(connection),
                successMessage = successMessage,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Commit request failed: ${e.javaClass.simpleName}: ${e.message}")
            SendResult(ok = false, message = "连接失败：${e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) {
            return ""
        }
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    }

    private companion object {
        private const val TAG = "TextBridge"
    }
}

object CommitProtocol {
    private fun normalizeBaseUrl(address: String): String {
        val base = if (address.startsWith("http://") || address.startsWith("https://")) {
            address.trimEnd('/')
        } else {
            "http://${address.trimEnd('/')}"
        }
        return base
    }

    fun normalizeCommitUrl(address: String): String {
        return "${normalizeBaseUrl(address)}/v1/commit"
    }

    fun normalizeKeyUrl(address: String): String {
        return "${normalizeBaseUrl(address)}/v1/key"
    }

    fun requestBody(requestId: String, text: String): ByteArray {
        return JSONObject()
            .put("id", requestId)
            .put("text", text)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun keyRequestBody(requestId: String, key: RemoteKey, modifiers: List<KeyModifier>): ByteArray {
        return JSONObject()
            .put("id", requestId)
            .put("key", key.wireValue)
            .put("modifiers", JSONArray(modifiers.map { it.wireValue }))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun parseResult(code: Int, responseBody: String, successMessage: String = "已发送"): SendResult {
        val json = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
        val status = json.optString("status", "")
        return if (code == HttpURLConnection.HTTP_OK && status == "ok") {
            SendResult(ok = true, message = successMessage)
        } else {
            SendResult(ok = false, message = mapFailure(code, status))
        }
    }

    fun mapFailure(code: Int, status: String): String {
        return when (status) {
            "invalid_request" -> "请求无效"
            "invalid_key" -> "按键无效"
            "unauthorized" -> "令牌错误"
            "busy_composing" -> "电脑正在组词"
            "sensitive_field" -> "目标输入框受保护"
            "text_too_large" -> "正文过长"
            "no_focused_input" -> "电脑没有输入焦点"
            "fcitx_unavailable" -> "Fcitx 插件不可用"
            else -> if (code > 0) "发送失败 ($code)" else "发送失败"
        }
    }
}
