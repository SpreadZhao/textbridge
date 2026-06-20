package io.github.textbridge.android

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface TextBridgeCommitClient {
    fun postCommit(address: String, token: String, requestId: String, text: String): SendResult
}

class CommitClient : TextBridgeCommitClient {
    override fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val body = CommitProtocol.requestBody(requestId, text)
            connection = (URL(CommitProtocol.normalizeCommitUrl(address)).openConnection() as HttpURLConnection).apply {
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
    fun normalizeCommitUrl(address: String): String {
        val base = if (address.startsWith("http://") || address.startsWith("https://")) {
            address.trimEnd('/')
        } else {
            "http://${address.trimEnd('/')}"
        }
        return "$base/v1/commit"
    }

    fun requestBody(requestId: String, text: String): ByteArray {
        return JSONObject()
            .put("id", requestId)
            .put("text", text)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun parseResult(code: Int, responseBody: String): SendResult {
        val json = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
        val status = json.optString("status", "")
        return if (code == HttpURLConnection.HTTP_OK && status == "ok") {
            SendResult(ok = true, message = "已发送")
        } else {
            SendResult(ok = false, message = mapFailure(code, status))
        }
    }

    fun mapFailure(code: Int, status: String): String {
        return when (status) {
            "invalid_request" -> "请求无效"
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
