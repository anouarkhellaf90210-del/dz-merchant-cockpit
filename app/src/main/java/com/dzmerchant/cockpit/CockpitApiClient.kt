package com.dzmerchant.cockpit

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class StoreItem(
    val id: String,
    val name: String,
    val slug: String,
    val isOpen: Boolean
)

data class MerchantOrderItem(
    val id: String,
    val ref: String,
    val status: String,
    val stage: String,
    val customerName: String,
    val customerPhone: String,
    val carrier: String,
    val wilaya: String,
    val total: Int,
    val itemsSummary: String,
    val relativeTime: String,
    val trackingToken: String
)

data class CockpitPayload(
    val storeId: String,
    val storeName: String,
    val storeSlug: String,
    val isStoreOpen: Boolean,
    val stores: List<StoreItem>,
    val gmvToday: Int,
    val ordersTodayCount: Int,
    val codInStreet: Int,
    val newCount: Int,
    val preparingCount: Int,
    val shippingCount: Int,
    val deliveredCount: Int,
    val orders: List<MerchantOrderItem>
)

object CockpitApiClient {
    private const val BASE_URL = "https://my-private-store-app.vercel.app"

    fun fetchCockpit(storeFilter: String = "all"): CockpitPayload? {
        return try {
            val endpoint = "$BASE_URL/api/merchant/cockpit?store=$storeFilter&_cb=${System.currentTimeMillis()}"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val raw = reader.readText()
            reader.close()

            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null

            val activeStore = root.optJSONObject("activeStore") ?: JSONObject()
            val storesArr = root.optJSONArray("stores")
            val storesList = mutableListOf<StoreItem>()
            if (storesArr != null) {
                for (i in 0 until storesArr.length()) {
                    val s = storesArr.getJSONObject(i)
                    storesList.add(
                        StoreItem(
                            id = s.optString("id"),
                            name = s.optString("name", "متجر"),
                            slug = s.optString("slug"),
                            isOpen = s.optBoolean("isOpen", true)
                        )
                    )
                }
            }

            val telemetry = root.optJSONObject("telemetry") ?: JSONObject()
            val counts = telemetry.optJSONObject("counts") ?: JSONObject()

            val ordersArr = root.optJSONArray("orders")
            val ordersList = mutableListOf<MerchantOrderItem>()
            if (ordersArr != null) {
                for (i in 0 until ordersArr.length()) {
                    val o = ordersArr.getJSONObject(i)
                    ordersList.add(
                        MerchantOrderItem(
                            id = o.optString("id"),
                            ref = o.optString("ref", "#---"),
                            status = o.optString("status", "new"),
                            stage = o.optString("stage", "new"),
                            customerName = o.optString("customerName", "زبون"),
                            customerPhone = o.optString("customerPhone", ""),
                            carrier = o.optString("carrier", "الجزائر"),
                            wilaya = o.optString("wilaya", "الجزائر"),
                            total = o.optInt("total", 0),
                            itemsSummary = o.optString("itemsSummary", "طلبية ×1"),
                            relativeTime = o.optString("relativeTime", "الآن"),
                            trackingToken = o.optString("trackingToken", "")
                        )
                    )
                }
            }

            CockpitPayload(
                storeId = activeStore.optString("id"),
                storeName = activeStore.optString("name", "Elegance"),
                storeSlug = activeStore.optString("slug", "dzball"),
                isStoreOpen = activeStore.optBoolean("isOpen", true),
                stores = storesList,
                gmvToday = telemetry.optInt("gmvToday", 0),
                ordersTodayCount = telemetry.optInt("ordersTodayCount", 0),
                codInStreet = telemetry.optInt("codInStreet", 0),
                newCount = counts.optInt("new", 0),
                preparingCount = counts.optInt("preparing", 0),
                shippingCount = counts.optInt("shipping", 0),
                deliveredCount = counts.optInt("delivered", 0),
                orders = ordersList
            )
        } catch (e: Exception) {
            null
        }
    }

    fun updateOrderStatus(orderId: String, newStatus: String): Boolean {
        return try {
            val conn = URL("$BASE_URL/api/merchant/cockpit").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("action", "update_order_status")
                put("orderId", orderId)
                put("newStatus", newStatus)
            }
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    fun toggleStoreStatus(storeId: String, isOpen: Boolean): Boolean {
        return try {
            val conn = URL("$BASE_URL/api/merchant/cockpit").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("action", "toggle_store_status")
                put("storeId", storeId)
                put("isOpen", isOpen)
            }
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }
}
