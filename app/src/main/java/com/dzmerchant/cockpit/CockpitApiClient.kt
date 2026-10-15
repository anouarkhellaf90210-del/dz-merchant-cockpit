package com.dzmerchant.cockpit

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class StoreSummary(
    val id: String,
    val name: String,
    val slug: String,
    val isOpen: Boolean
)

data class MerchantOrderItem(
    val id: String,
    val ref: String,
    val customerName: String,
    val customerPhone: String,
    val carrier: String,
    val wilaya: String,
    val total: Int,
    val itemsSummary: String,
    val status: String,
    val stage: String,
    val relativeTime: String
)

data class CockpitPayload(
    val storeName: String,
    val storeId: String,
    val isStoreOpen: Boolean,
    val gmvToday: Int,
    val ordersTodayCount: Int,
    val codInStreet: Int,
    val newCount: Int,
    val preparingCount: Int,
    val shippingCount: Int,
    val deliveredCount: Int,
    val stores: List<StoreSummary>,
    val orders: List<MerchantOrderItem>
)

object CockpitApiClient {
    private const val BASE_URL = "https://my-private-store-app.vercel.app/api/merchant/cockpit"

    fun fetchCockpit(storeSlug: String = "all"): CockpitPayload? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$BASE_URL?store=$storeSlug&_cb=${System.currentTimeMillis()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (conn.responseCode != 200) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val raw = reader.readText()
            reader.close()

            val json = JSONObject(raw)
            val storesArr = json.optJSONArray("stores")
            val storesList = mutableListOf<StoreSummary>()
            if (storesArr != null) {
                for (i in 0 until storesArr.length()) {
                    val s = storesArr.getJSONObject(i)
                    storesList.add(
                        StoreSummary(
                            id = s.optString("id", ""),
                            name = s.optString("name", "متجر"),
                            slug = s.optString("slug", "all"),
                            isOpen = s.optBoolean("isOpen", true)
                        )
                    )
                }
            }

            val ordersArr = json.optJSONArray("orders")
            val ordersList = mutableListOf<MerchantOrderItem>()
            if (ordersArr != null) {
                for (i in 0 until ordersArr.length()) {
                    val o = ordersArr.getJSONObject(i)
                    ordersList.add(
                        MerchantOrderItem(
                            id = o.optString("id", ""),
                            ref = o.optString("ref", "#---"),
                            customerName = o.optString("customerName", "زبون"),
                            customerPhone = o.optString("customerPhone", ""),
                            carrier = o.optString("carrier", "شريحة"),
                            wilaya = o.optString("wilaya", "الجزائر"),
                            total = o.optInt("total", 0),
                            itemsSummary = o.optString("itemsSummary", ""),
                            status = o.optString("status", "pending"),
                            stage = o.optString("stage", "new"),
                            relativeTime = o.optString("relativeTime", "الآن")
                        )
                    )
                }
            }

            CockpitPayload(
                storeName = json.optString("storeName", "Elegance"),
                storeId = json.optString("storeId", ""),
                isStoreOpen = json.optBoolean("isStoreOpen", true),
                gmvToday = json.optInt("gmvToday", 0),
                ordersTodayCount = json.optInt("ordersTodayCount", 0),
                codInStreet = json.optInt("codInStreet", 0),
                newCount = json.optInt("newCount", 0),
                preparingCount = json.optInt("preparingCount", 0),
                shippingCount = json.optInt("shippingCount", 0),
                deliveredCount = json.optInt("deliveredCount", 0),
                stores = storesList,
                orders = ordersList
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun updateOrderStatus(orderId: String, nextStatus: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BASE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            val body = JSONObject().apply {
                put("action", "update_order_status")
                put("orderId", orderId)
                put("status", nextStatus)
            }
            val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
            writer.write(body.toString())
            writer.flush()
            writer.close()
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun toggleStoreStatus(storeId: String, isOpen: Boolean): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BASE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            val body = JSONObject().apply {
                put("action", "toggle_store")
                put("storeId", storeId)
                put("isOpen", isOpen)
            }
            val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
            writer.write(body.toString())
            writer.flush()
            writer.close()
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun notifyLocalCustomerCapsuleRefresh() {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://127.0.0.1:49210/refresh?_cb=${System.currentTimeMillis()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 600
                readTimeout = 600
            }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }
}
