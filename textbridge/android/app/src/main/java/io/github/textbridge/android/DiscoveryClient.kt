package io.github.textbridge.android

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID

class DiscoveryClient(
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    fun discover(discoveryPort: Int): List<DiscoveryOffer> {
        val requestId = UUID.randomUUID().toString()
        val requestBytes = DiscoveryProtocol.requestBytes(requestId)
        val offers = LinkedHashMap<String, DiscoveryOffer>()
        val deadline = elapsedRealtime() + DISCOVERY_TIMEOUT_MS

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in broadcastTargets()) {
                    socket.send(
                        DatagramPacket(
                            requestBytes,
                            requestBytes.size,
                            target,
                            discoveryPort,
                        ),
                    )
                }

                while (true) {
                    val remainingMs = deadline - elapsedRealtime()
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

                    val offer = DiscoveryProtocol.parseOffer(response, requestId) ?: continue
                    offers.putIfAbsent(offer.address, offer)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        return offers.values.toList()
    }

    private fun broadcastTargets(): List<InetAddress> {
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
                    addTarget(address.broadcast ?: continue)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate broadcast targets: ${e.javaClass.simpleName}: ${e.message}")
        }

        return targets.values.toList()
    }

    private companion object {
        private const val TAG = "TextBridge"
        private const val DISCOVERY_BROADCAST_ADDRESS = "255.255.255.255"
        private const val MAX_DISCOVERY_DATAGRAM_BYTES = 2048
    }
}

object DiscoveryProtocol {
    fun requestBytes(requestId: String): ByteArray {
        return JSONObject()
            .put("v", 1)
            .put("type", "textbridge.discover")
            .put("id", requestId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun parseOffer(packet: DatagramPacket, requestId: String): DiscoveryOffer? {
        val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
        return parseOffer(payload, requestId)
    }

    fun parseOffer(payload: String, requestId: String): DiscoveryOffer? {
        return try {
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
}
