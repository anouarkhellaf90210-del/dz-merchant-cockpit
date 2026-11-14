package com.dzmerchant.cockpit

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class SupabaseRealtimeClient(
    private val onOrderChange: () -> Unit
) {
    @Volatile
    var isConnected: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private var currentUrl = ""
    private var currentKey = ""
    private var currentFilter = ""
    private var socket: SSLSocket? = null
    private var workerThread: Thread? = null

    fun ensureStarted(supabaseUrl: String, anonKey: String, filter: String = "") {
        if (supabaseUrl.isBlank() || anonKey.isBlank()) return
        val cleanUrl = supabaseUrl.trim().removeSuffix("/")
        val cleanKey = anonKey.trim()
        val cleanFilter = filter.trim()
        if (running.get() && currentUrl == cleanUrl && currentKey == cleanKey && currentFilter == cleanFilter) {
            return
        }
        stop()
        currentUrl = cleanUrl
        currentKey = cleanKey
        currentFilter = cleanFilter
        running.set(true)

        workerThread = thread(start = true, isDaemon = true, name = "CockpitRealtimeWS") {
            while (running.get()) {
                try {
                    connectAndListen()
                } catch (_: Exception) {
                } finally {
                    isConnected = false
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }
                if (running.get()) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { workerThread?.interrupt() } catch (_: Exception) {}
        workerThread = null
    }

    private fun connectAndListen() {
        val parsed = URL(currentUrl)
        val host = parsed.host
        val port = if (parsed.port != -1) parsed.port else 443
        val path = "/realtime/v1/websocket?apikey=$currentKey&vsn=1.0.0"

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(host, port) as SSLSocket
        ssl.soTimeout = 45000
        ssl.startHandshake()
        socket = ssl

        val out = BufferedOutputStream(ssl.outputStream)
        val inp = BufferedInputStream(ssl.inputStream)

        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val wsKey = Base64.getEncoder().encodeToString(keyBytes)

        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.UTF_8))
        out.flush()

        val headerBuf = StringBuilder()
        var prev = -1
        var lineEndCount = 0
        while (true) {
            val b = inp.read()
            if (b == -1) throw IllegalStateException("EOF during WS handshake")
            headerBuf.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) {
                if (lineEndCount == 1) break
                lineEndCount = 1
            } else if (b != '\r'.code && b != '\n'.code) {
                lineEndCount = 0
            }
            prev = b
        }
        if (!headerBuf.toString().contains("101")) {
            throw IllegalStateException("WS upgrade failed")
        }

        isConnected = true
        val topic = if (currentFilter.isNotBlank()) "realtime:public:orders:$currentFilter" else "realtime:public:orders"
        val changeObj = JSONObject().apply {
            put("event", "*")
            put("schema", "public")
            put("table", "orders")
            if (currentFilter.isNotBlank()) {
                put("filter", currentFilter)
            }
        }
        val joinPayload = JSONObject().apply {
            put("topic", topic)
            put("event", "phx_join")
            put("ref", "1")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("postgres_changes", JSONArray().apply { put(changeObj) })
                })
            })
        }
        sendTextFrame(out, joinPayload.toString())

        val hbThread = thread(start = true, isDaemon = true, name = "CockpitWSHeartbeat") {
            var refId = 2
            while (running.get() && isConnected) {
                try {
                    Thread.sleep(25000)
                    if (!running.get() || !isConnected) break
                    val hb = JSONObject().apply {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", JSONObject())
                        put("ref", (refId++).toString())
                    }
                    synchronized(out) {
                        sendTextFrame(out, hb.toString())
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            while (running.get()) {
                val msg = readTextFrame(inp) ?: break
                if (msg.contains("postgres_changes") || msg.contains("INSERT") || msg.contains("UPDATE")) {
                    onOrderChange()
                }
            }
        } finally {
            isConnected = false
            hbThread.interrupt()
        }
    }

    private fun sendTextFrame(out: OutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val mask = ByteArray(4)
        SecureRandom().nextBytes(mask)
        out.write(0x81)
        when {
            data.size <= 125 -> out.write(0x80 or data.size)
            data.size <= 65535 -> {
                out.write(0x80 or 126)
                out.write((data.size ushr 8) and 0xFF)
                out.write(data.size and 0xFF)
            }
            else -> throw IllegalArgumentException("Payload too large")
        }
        out.write(mask)
        for (i in data.indices) {
            out.write(data[i].toInt() xor mask[i % 4].toInt())
        }
        out.flush()
    }

    private fun readTextFrame(inp: InputStream): String? {
        val b1 = inp.read()
        if (b1 == -1) return null
        val b2 = inp.read()
        if (b2 == -1) return null
        val opcode = b1 and 0x0F
        if (opcode == 0x08) return null
        var len = (b2 and 0x7F).toLong()
        if (len == 126L) {
            len = (((inp.read() and 0xFF) shl 8) or (inp.read() and 0xFF)).toLong()
        } else if (len == 127L) {
            len = 0
            for (i in 0 until 8) len = (len shl 8) or (inp.read() and 0xFF).toLong()
        }
        val buf = ByteArray(len.toInt())
        var off = 0
        while (off < buf.size) {
            val r = inp.read(buf, off, buf.size - off)
            if (r == -1) return null
            off += r
        }
        return if (opcode == 0x01 || opcode == 0x00) String(buf, Charsets.UTF_8) else ""
    }
}
