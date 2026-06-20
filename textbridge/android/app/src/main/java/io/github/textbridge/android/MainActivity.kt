package io.github.textbridge.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var addressEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var scanButton: Button
    private lateinit var sendButton: Button
    private lateinit var statusView: TextView

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressEdit = findViewById(R.id.addressEdit)
        tokenEdit = findViewById(R.id.tokenEdit)
        bodyEdit = findViewById(R.id.bodyEdit)
        scanButton = findViewById(R.id.scanButton)
        sendButton = findViewById(R.id.sendButton)
        statusView = findViewById(R.id.statusView)

        bodyEdit.imeOptions = EditorInfo.IME_ACTION_NONE

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        addressEdit.setText(prefs.getString(KEY_ADDRESS, ""))
        tokenEdit.setText(prefs.getString(KEY_TOKEN, ""))

        scanButton.setOnClickListener { scanForComputers() }
        sendButton.setOnClickListener { sendCurrentText() }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun scanForComputers() {
        setScanning(true)
        statusView.text = "状态：正在扫描..."
        val startedAt = SystemClock.elapsedRealtime()

        executor.execute {
            val offers = discoverComputers()
            val remainingMs = DISCOVERY_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remainingMs > 0) {
                try {
                    Thread.sleep(remainingMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            runOnUiThread {
                setScanning(false)
                when {
                    offers.isEmpty() -> {
                        statusView.text = "状态：未发现电脑"
                    }
                    offers.size == 1 -> {
                        applyDiscoveryOffer(offers.first())
                    }
                    else -> {
                        statusView.text = "状态：发现 ${offers.size} 台电脑"
                        showDiscoveryChooser(offers)
                    }
                }
            }
        }
    }

    private fun setScanning(scanning: Boolean) {
        scanButton.isEnabled = !scanning
        scanButton.text = if (scanning) getString(R.string.scan_computer_progress) else getString(R.string.scan_computer)
    }

    private fun discoverComputers(): List<DiscoveryOffer> {
        val requestId = UUID.randomUUID().toString()
        val requestBytes = JSONObject()
            .put("v", 1)
            .put("type", "textbridge.discover")
            .put("id", requestId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val offers = LinkedHashMap<String, DiscoveryOffer>()
        val deadline = SystemClock.elapsedRealtime() + DISCOVERY_TIMEOUT_MS

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in discoveryBroadcastTargets()) {
                    socket.send(
                        DatagramPacket(
                            requestBytes,
                            requestBytes.size,
                            target,
                            DISCOVERY_PORT,
                        ),
                    )
                }

                while (true) {
                    val remainingMs = deadline - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0) {
                        break
                    }

                    socket.soTimeout = remainingMs.coerceAtMost(250L).toInt()
                    val response = DatagramPacket(ByteArray(MAX_DISCOVERY_DATAGRAM_BYTES), MAX_DISCOVERY_DATAGRAM_BYTES)
                    try {
                        socket.receive(response)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }

                    val offer = parseDiscoveryOffer(response, requestId) ?: continue
                    offers.putIfAbsent("${offer.host}:${offer.port}", offer)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        return offers.values.toList()
    }

    private fun discoveryBroadcastTargets(): List<InetAddress> {
        val targets = LinkedHashMap<String, InetAddress>()

        fun addTarget(address: InetAddress) {
            if (!address.isLoopbackAddress) {
                targets[address.hostAddress ?: address.hostName] = address
            }
        }

        try {
            addTarget(InetAddress.getByName(DISCOVERY_BROADCAST_ADDRESS))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add limited broadcast target: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                for (address in networkInterface.interfaceAddresses) {
                    val broadcast = address.broadcast ?: continue
                    addTarget(broadcast)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate broadcast targets: ${e.javaClass.simpleName}: ${e.message}")
        }

        return targets.values.toList()
    }

    private fun parseDiscoveryOffer(packet: DatagramPacket, requestId: String): DiscoveryOffer? {
        return try {
            val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            val json = JSONObject(payload)
            if (json.optInt("v") != 1 || json.optString("type") != "textbridge.offer") {
                return null
            }
            if (json.optString("id") != requestId) {
                return null
            }

            val host = json.optString("host").trim()
            val port = json.optInt("port", -1)
            if (host.isBlank() || port !in 1..65535) {
                return null
            }

            val name = json.optString("name").trim()
            DiscoveryOffer(
                name = if (name.isBlank()) host else name,
                host = host,
                port = port,
                version = json.optString("version"),
                auth = json.optString("auth"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun showDiscoveryChooser(offers: List<DiscoveryOffer>) {
        val labels = offers.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.select_computer)
            .setItems(labels) { _, which ->
                applyDiscoveryOffer(offers[which])
            }
            .show()
    }

    private fun applyDiscoveryOffer(offer: DiscoveryOffer) {
        val address = "${offer.host}:${offer.port}"
        addressEdit.setText(address)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address)
            .apply()
        statusView.text = "状态：已选择 ${offer.label}"
    }

    private fun sendCurrentText() {
        val address = addressEdit.text.toString().trim()
        val token = tokenEdit.text.toString()
        val text = bodyEdit.text.toString()

        when {
            address.isBlank() -> {
                statusView.text = "状态：请填写电脑地址"
                return
            }
            token.isBlank() -> {
                statusView.text = "状态：请填写访问令牌"
                return
            }
            text.isEmpty() -> {
                statusView.text = "状态：正文为空"
                return
            }
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_TOKEN, token)
            .apply()

        setSending(true)
        val requestId = UUID.randomUUID().toString()

        executor.execute {
            val result = postCommit(address, token, requestId, text)
            runOnUiThread {
                if (result.ok) {
                    bodyEdit.text.clear()
                }
                statusView.text = "状态：${result.message}"
                setSending(false)
            }
        }
    }

    private fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        sendButton.text = if (sending) "发送中..." else getString(R.string.send)
    }

    private fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(normalizeCommitUrl(address))
            val body = JSONObject()
                .put("id", requestId)
                .put("text", text)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            connection = (url.openConnection() as HttpURLConnection).apply {
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
            val code = connection.responseCode
            val responseBody = readResponseBody(connection, code)
            val json = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
            val status = json.optString("status", "")

            if (code == HttpURLConnection.HTTP_OK && status == "ok") {
                SendResult(ok = true, message = "已发送")
            } else {
                SendResult(ok = false, message = mapFailure(code, status))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Commit request failed: ${e.javaClass.simpleName}: ${e.message}")
            SendResult(ok = false, message = "连接失败：${e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeCommitUrl(address: String): String {
        val base = if (address.startsWith("http://") || address.startsWith("https://")) {
            address.trimEnd('/')
        } else {
            "http://${address.trimEnd('/')}"
        }
        return "$base/v1/commit"
    }

    private fun readResponseBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) {
            return ""
        }
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    }

    private fun mapFailure(code: Int, status: String): String {
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

    private data class SendResult(
        val ok: Boolean,
        val message: String,
    )

    private data class DiscoveryOffer(
        val name: String,
        val host: String,
        val port: Int,
        val version: String,
        val auth: String,
    ) {
        val label: String
            get() = "$name $host:$port"
    }

    companion object {
        private const val PREFS_NAME = "textbridge"
        private const val KEY_ADDRESS = "address"
        private const val KEY_TOKEN = "token"
        private const val TAG = "TextBridge"
        private const val DISCOVERY_BROADCAST_ADDRESS = "255.255.255.255"
        private const val DISCOVERY_PORT = 17322
        private const val DISCOVERY_TIMEOUT_MS = 2000L
        private const val MAX_DISCOVERY_DATAGRAM_BYTES = 2048
    }
}
