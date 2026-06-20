package io.github.textbridge.android

import org.json.JSONArray
import org.json.JSONObject

object SendHistoryCodec {
    fun decode(payload: String?): List<SendHistoryItem> {
        if (payload.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val text = item.optString("text")
                    val sentAtMillis = item.optLong("sentAtMillis", 0L)
                    val address = item.optString("address").trim()
                    if (id.isBlank() || text.isEmpty() || sentAtMillis <= 0L || address.isBlank()) {
                        continue
                    }
                    add(
                        SendHistoryItem(
                            id = id,
                            text = text,
                            sentAtMillis = sentAtMillis,
                            address = address,
                            transportMode = TransportMode.fromStorage(item.optString("transportMode")),
                        ),
                    )
                }
            }.take(MAX_SEND_HISTORY_ITEMS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(items: List<SendHistoryItem>): String {
        val array = JSONArray()
        items.take(MAX_SEND_HISTORY_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("sentAtMillis", item.sentAtMillis)
                    .put("address", item.address)
                    .put("transportMode", item.transportMode.storageValue),
            )
        }
        return array.toString()
    }

    fun prepend(existing: List<SendHistoryItem>, item: SendHistoryItem): List<SendHistoryItem> {
        return (listOf(item) + existing.filterNot { it.id == item.id })
            .take(MAX_SEND_HISTORY_ITEMS)
    }

    fun remove(existing: List<SendHistoryItem>, itemId: String): List<SendHistoryItem> {
        return existing.filterNot { it.id == itemId }
    }
}
