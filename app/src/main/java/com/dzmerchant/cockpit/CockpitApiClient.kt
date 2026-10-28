package com.dzmerchant.cockpit

import org.json.JSONArray
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
    var isOpen: Boolean
)

data class OrderDetailItem(
    val id: String,
    val productId: String?,
    val variantId: String?,
    var name: String,
    var variant: String,
    var quantity: Int,
    var unitPrice: Int
) {
    val lineTotal: Int
        get() = unitPrice * quantity
}

data class MerchantOrderItem(
    val id: String,
    val ref: String,
    var customerName: String,
    var customerPhone: String,
    val carrier: String,
    var wilaya: String,
    var commune: String,
    var notes: String,
    val deliveryType: String,
    var subtotal: Int,
    var shippingCost: Int,
    var total: Int,
    var itemsSummary: String,
    var status: String,
    var stage: String,
    val relativeTime: String,
    val isPickup: Boolean = false,
    val createdAtMs: Long,
    val items: MutableList<OrderDetailItem>
)

data class CockpitPayload(
    val storeName: String,
    val storeId: String,
    var isStoreOpen: Boolean,
    val gmvToday: Int,
    val ordersTodayCount: Int,
    var codInStreet: Int,
    var newCount: Int,
    var preparingCount: Int,
    var shippingCount: Int,
    var deliveredCount: Int,
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
                    val totalVal = o.optInt("total", 0)
                    val shipVal = o.optInt("shippingCost", 0)
                    val subVal = o.optInt("subtotal", (totalVal - shipVal).coerceAtLeast(0))
                    val rawSummary = o.optString("itemsSummary", "طلبية منتج ×1")

                    val parsedItems = mutableListOf<OrderDetailItem>()
                    val itemsJson = o.optJSONArray("items")
                    if (itemsJson != null && itemsJson.length() > 0) {
                        for (j in 0 until itemsJson.length()) {
                            val itObj = itemsJson.getJSONObject(j)
                            val qty = itObj.optInt("quantity", 1).coerceAtLeast(1)
                            val uPrice = itObj.optInt("unitPrice", subVal / qty)
                            parsedItems.add(
                                OrderDetailItem(
                                    id = itObj.optString("id", "item-$j"),
                                    productId = itObj.optString("productId", "").takeIf { it.isNotEmpty() },
                                    variantId = itObj.optString("variantId", "").takeIf { it.isNotEmpty() },
                                    name = itObj.optString("name", "منتج"),
                                    variant = itObj.optString("variant", ""),
                                    quantity = qty,
                                    unitPrice = uPrice
                                )
                            )
                        }
                    } else {
                        parsedItems.add(
                            OrderDetailItem(
                                id = "fallback-${o.optString("id", "$i")}",
                                productId = null,
                                variantId = null,
                                name = rawSummary.replace(" ×1", "").trim().ifEmpty { "منتج" },
                                variant = "",
                                quantity = 1,
                                unitPrice = subVal
                            )
                        )
                    }

                    ordersList.add(
                        MerchantOrderItem(
                            id = o.optString("id", ""),
                            ref = o.optString("ref", "#---"),
                            customerName = o.optString("customerName", "زبون"),
                            customerPhone = o.optString("customerPhone", ""),
                            carrier = o.optString("carrier", "شريحة"),
                            wilaya = o.optString("wilaya", "43 - ميلة"),
                            commune = o.optString("commune", ""),
                            notes = o.optString("notes", ""),
                            deliveryType = o.optString("deliveryType", "home"),
                            subtotal = subVal,
                            shippingCost = shipVal,
                            total = totalVal,
                            itemsSummary = rawSummary,
                            status = o.optString("status", "pending"),
                            stage = o.optString("stage", "new"),
                            relativeTime = o.optString("relativeTime", "الآن"),
                            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                            items = parsedItems
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

    fun updateOrderDetails(order: MerchantOrderItem): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BASE_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9000
                readTimeout = 9000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            val itemsArr = JSONArray()
            for (it in order.items) {
                itemsArr.put(
                    JSONObject().apply {
                        put("id", it.id)
                        if (it.productId != null) put("productId", it.productId)
                        if (it.variantId != null) put("variantId", it.variantId)
                        put("name", it.name)
                        put("variant", it.variant)
                        put("quantity", it.quantity)
                        put("unitPrice", it.unitPrice)
                    }
                )
            }
            val body = JSONObject().apply {
                put("action", "update_order_details")
                put("orderId", order.id)
                put("customerName", order.customerName)
                put("customerPhone", order.customerPhone)
                put("wilaya", order.wilaya)
                put("commune", order.commune)
                put("notes", order.notes)
                put("shippingCost", order.shippingCost)
                put("total", order.total)
                put("items", itemsArr)
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

    fun notifyLocalCustomerCapsuleRefresh(nextStatus: String) {
        val station = when (nextStatus) {
            "confirmed", "preparing", "processing" -> 2
            "out_for_delivery", "shipped", "shipping" -> 3
            "delivered", "completed" -> 4
            else -> 1
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://127.0.0.1:49210/set-station?station=$station&status=$nextStatus&_cb=${System.currentTimeMillis()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 800
                readTimeout = 800
            }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }
}
