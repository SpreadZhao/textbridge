package io.github.textbridge.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

const val TEXTBRIDGE_BLUETOOTH_SERVICE_UUID = "6f6f3b6e-8ff4-4a5f-8f24-0f8e7f4a7d42"

interface TextBridgeBluetoothDeviceRepository {
    fun pairedDevices(): List<BluetoothDeviceChoice>
}

object EmptyBluetoothDeviceRepository : TextBridgeBluetoothDeviceRepository {
    override fun pairedDevices(): List<BluetoothDeviceChoice> = emptyList()
}

class BluetoothDeviceRepository(context: Context) : TextBridgeBluetoothDeviceRepository {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun pairedDevices(): List<BluetoothDeviceChoice> {
        val adapter = bluetoothAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices
                .orEmpty()
                .map { device ->
                    BluetoothDeviceChoice(
                        name = device.name.orEmpty(),
                        address = device.address,
                    )
                }
                .sortedWith(compareBy<BluetoothDeviceChoice> { it.name.ifBlank { it.address } }.thenBy { it.address })
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing Bluetooth permission while reading paired devices")
            emptyList()
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        return appContext.getSystemService(BluetoothManager::class.java)?.adapter
    }

    private companion object {
        private const val TAG = "TextBridge"
    }
}

interface TextBridgeBluetoothCommitClient {
    fun postCommit(deviceAddress: String, token: String, requestId: String, text: String): SendResult
    fun postKey(
        deviceAddress: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult
}

object UnavailableBluetoothCommitClient : TextBridgeBluetoothCommitClient {
    override fun postCommit(deviceAddress: String, token: String, requestId: String, text: String): SendResult {
        return SendResult(ok = false, message = "蓝牙不可用")
    }

    override fun postKey(
        deviceAddress: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult {
        return SendResult(ok = false, message = "蓝牙不可用")
    }
}

class BluetoothCommitClient(context: Context) : TextBridgeBluetoothCommitClient {
    private val appContext = context.applicationContext
    private val serviceUuid = UUID.fromString(TEXTBRIDGE_BLUETOOTH_SERVICE_UUID)

    override fun postCommit(deviceAddress: String, token: String, requestId: String, text: String): SendResult {
        return postFrame(
            deviceAddress = deviceAddress,
            frame = BluetoothCommitProtocol.commitRequestBody(
                token = token,
                requestId = requestId,
                text = text,
            ),
            successMessage = "已发送",
        )
    }

    override fun postKey(
        deviceAddress: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult {
        return postFrame(
            deviceAddress = deviceAddress,
            frame = BluetoothCommitProtocol.keyRequestBody(
                token = token,
                requestId = requestId,
                key = key,
                modifiers = modifiers,
            ),
            successMessage = "按键已发送",
        )
    }

    @SuppressLint("MissingPermission")
    private fun postFrame(deviceAddress: String, frame: ByteArray, successMessage: String): SendResult {
        if (!BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
            return SendResult(ok = false, message = "蓝牙设备无效")
        }

        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return SendResult(ok = false, message = "蓝牙不可用")
        if (!adapter.isEnabled) {
            return SendResult(ok = false, message = "蓝牙未开启")
        }

        return try {
            val device = adapter.getRemoteDevice(deviceAddress)
            device.createRfcommSocketToServiceRecord(serviceUuid).use { socket ->
                socket.connect()
                val output = socket.outputStream
                output.write(frame)
                output.write('\n'.code)
                output.flush()
                val responseBody = readBluetoothFrame(socket.inputStream)
                BluetoothCommitProtocol.parseResult(responseBody, successMessage)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission denied: ${e.javaClass.simpleName}: ${e.message}")
            SendResult(ok = false, message = "请授权蓝牙权限")
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth request failed: ${e.javaClass.simpleName}: ${e.message}")
            SendResult(ok = false, message = "蓝牙连接失败：${e.javaClass.simpleName}")
        }
    }

    private fun readBluetoothFrame(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1 || next == '\n'.code) {
                break
            }
            if (buffer.size() >= MAX_BLUETOOTH_FRAME_BYTES) {
                throw IOException("Bluetooth response too large")
            }
            buffer.write(next)
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        private const val TAG = "TextBridge"
    }
}

object BluetoothCommitProtocol {
    fun commitRequestBody(token: String, requestId: String, text: String): ByteArray {
        return JSONObject()
            .put("v", 1)
            .put("id", requestId)
            .put("token", token)
            .put("action", "commit")
            .put("text", text)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun keyRequestBody(token: String, requestId: String, key: RemoteKey, modifiers: List<KeyModifier>): ByteArray {
        return JSONObject()
            .put("v", 1)
            .put("id", requestId)
            .put("token", token)
            .put("action", "key")
            .put("key", key.wireValue)
            .put("modifiers", JSONArray(modifiers.map { it.wireValue }))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun parseResult(responseBody: String, successMessage: String = "已发送"): SendResult {
        return try {
            val json = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
            val status = json.optString("status", "")
            if (status == "ok") {
                SendResult(ok = true, message = successMessage)
            } else {
                SendResult(ok = false, message = CommitProtocol.mapFailure(0, status))
            }
        } catch (e: Exception) {
            SendResult(ok = false, message = "发送失败")
        }
    }
}

private const val MAX_BLUETOOTH_FRAME_BYTES = 20 * 1024
