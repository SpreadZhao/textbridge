package io.github.textbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendHistoryCodecTest {
    @Test
    fun encodeDecodePreservesFullText() {
        val item = SendHistoryItem(
            id = "first",
            text = "hello\nworld",
            sentAtMillis = 1710000000000L,
            address = "192.168.1.10:17321",
        )

        val decoded = SendHistoryCodec.decode(SendHistoryCodec.encode(listOf(item)))

        assertEquals(listOf(item), decoded)
    }

    @Test
    fun decodeBadJsonReturnsEmptyList() {
        assertTrue(SendHistoryCodec.decode("not-json").isEmpty())
    }

    @Test
    fun decodeSkipsInvalidItems() {
        val decoded = SendHistoryCodec.decode(
            """
            [
              {"id":"","text":"missing id","sentAtMillis":1710000000000,"address":"192.168.1.10:17321"},
              {"id":"missing_text","text":"","sentAtMillis":1710000000000,"address":"192.168.1.10:17321"},
              {"id":"valid","text":"ok","sentAtMillis":1710000000000,"address":"192.168.1.10:17321"}
            ]
            """.trimIndent(),
        )

        assertEquals(1, decoded.size)
        assertEquals("valid", decoded.first().id)
    }

    @Test
    fun prependAddsNewestFirstAndTrimsToLimit() {
        val existing = (0 until MAX_SEND_HISTORY_ITEMS).map { index ->
            SendHistoryItem(
                id = "old-$index",
                text = "old $index",
                sentAtMillis = 1710000000000L + index,
                address = "192.168.1.10:17321",
            )
        }
        val newest = SendHistoryItem(
            id = "newest",
            text = "new",
            sentAtMillis = 1710000009999L,
            address = "192.168.1.10:17321",
        )

        val updated = SendHistoryCodec.prepend(existing, newest)

        assertEquals(MAX_SEND_HISTORY_ITEMS, updated.size)
        assertEquals(newest, updated.first())
        assertEquals("old-${MAX_SEND_HISTORY_ITEMS - 2}", updated.last().id)
    }

    @Test
    fun removeDeletesMatchingId() {
        val first = SendHistoryItem(
            id = "first",
            text = "first text",
            sentAtMillis = 1710000000000L,
            address = "192.168.1.10:17321",
        )
        val second = first.copy(id = "second", text = "second text")

        assertEquals(listOf(second), SendHistoryCodec.remove(listOf(first, second), "first"))
    }
}
