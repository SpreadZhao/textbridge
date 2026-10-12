package io.github.textbridge.android

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitClientTest {
    @Test
    fun httpCommitAndKeyRequestsDoNotSendAuthorization() {
        for (isKey in listOf(false, true)) {
            ServerSocket(0).use { server ->
                val receivedHeaders = CompletableFuture<List<String>>()
                val worker = thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = mutableListOf<String>()
                            while (true) {
                                val line = reader.readLine() ?: error("Unexpected end of request")
                                if (line.isEmpty()) break
                                headers += line
                            }
                            val bodyLength = headers.first { it.startsWith("Content-Length:", true) }
                                .substringAfter(':').trim().toInt()
                            repeat(bodyLength) { check(reader.read() >= 0) }
                            val body = "{\"status\":\"ok\"}"
                            socket.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n" +
                                    "Content-Type: application/json\r\nConnection: close\r\n\r\n$body").toByteArray(),
                            )
                            receivedHeaders.complete(headers)
                        }
                    } catch (exception: Exception) {
                        receivedHeaders.completeExceptionally(exception)
                    }
                }
                try {
                    val client = CommitClient()
                    val address = "127.0.0.1:${server.localPort}"
                    val result = if (isKey) {
                        client.postKey(address, "request-id", RemoteKey.RETURN, emptyList())
                    } else {
                        client.postCommit(address, "request-id", "hello")
                    }

                    val headers = receivedHeaders.get(5, TimeUnit.SECONDS)
                    assertTrue(result.ok)
                    assertEquals("POST /v1/${if (isKey) "key" else "commit"} HTTP/1.1", headers.first())
                    assertFalse(headers.any { it.startsWith("Authorization:", true) })
                } finally {
                    server.close()
                    worker.join(5000)
                }
            }
        }
    }
}
