package io.github.textbridge.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryProtocolTest {
    @Test
    fun parseValidOffer() {
        val offer = DiscoveryProtocol.parseOffer(
            payload = JSONObject()
                .put("v", 1)
                .put("type", "textbridge.offer")
                .put("id", "request-id")
                .put("name", "zephyrus-m16")
                .put("host", "192.168.1.10")
                .put("port", 17321)
                .put("version", "0.1.0")
                .put("auth", "bearer")
                .toString(),
            requestId = "request-id",
        )

        assertNotNull(offer)
        assertEquals("zephyrus-m16", offer!!.name)
        assertEquals("192.168.1.10:17321", offer.address)
        assertEquals("bearer", offer.auth)
    }

    @Test
    fun parseOfferRejectsWrongRequestId() {
        val offer = DiscoveryProtocol.parseOffer(
            payload = """{"v":1,"type":"textbridge.offer","id":"old","host":"192.168.1.10","port":17321}""",
            requestId = "new",
        )

        assertNull(offer)
    }

    @Test
    fun parseOfferRejectsWrongTypeAndVersion() {
        assertNull(
            DiscoveryProtocol.parseOffer(
                payload = """{"v":2,"type":"textbridge.offer","id":"request-id","host":"192.168.1.10","port":17321}""",
                requestId = "request-id",
            ),
        )
        assertNull(
            DiscoveryProtocol.parseOffer(
                payload = """{"v":1,"type":"textbridge.discover","id":"request-id","host":"192.168.1.10","port":17321}""",
                requestId = "request-id",
            ),
        )
    }

    @Test
    fun parseOfferRejectsInvalidHostAndPort() {
        assertNull(
            DiscoveryProtocol.parseOffer(
                payload = """{"v":1,"type":"textbridge.offer","id":"request-id","host":"","port":17321}""",
                requestId = "request-id",
            ),
        )
        assertNull(
            DiscoveryProtocol.parseOffer(
                payload = """{"v":1,"type":"textbridge.offer","id":"request-id","host":"192.168.1.10","port":70000}""",
                requestId = "request-id",
            ),
        )
    }

    @Test
    fun requestBytesUsesDiscoveryWireShape() {
        val json = JSONObject(String(DiscoveryProtocol.requestBytes("request-id")))

        assertEquals(1, json.getInt("v"))
        assertEquals("textbridge.discover", json.getString("type"))
        assertEquals("request-id", json.getString("id"))
    }
}
