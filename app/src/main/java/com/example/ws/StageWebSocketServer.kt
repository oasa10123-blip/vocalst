package com.example.ws

import android.util.Base64
import com.example.model.WsServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lightweight RFC-6455 compliant WebSocket server listening on port 4444 (default)
 * for remote live-stage key/mode/preset switching from pedalboards, laptops, DAWs, or MIDI bridges.
 */
class StageWebSocketServer(
    private val onMessageReceived: (clientAddress: String, message: String) -> String
) {
    private val _status = MutableStateFlow(WsServerStatus())
    val status: StateFlow<WsServerStatus> = _status.asStateFlow()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<ClientSession>()

    fun start(port: Int = 4444) {
        if (running.get() && _status.value.port == port) return
        stop()

        running.set(true)
        acceptThread = thread(name = "VocalStage-WS-$port") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss

                _status.value = WsServerStatus(
                    isRunning = true,
                    port = port,
                    localUrls = discoverLocalWsUrls(port),
                    connectedClients = 0,
                    lastError = null
                )

                while (running.get() && !ss.isClosed) {
                    val socket = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val session = ClientSession(socket)
                    clients.add(session)
                    updateClientCount()
                    thread(name = "WS-Client-${socket.inetAddress?.hostAddress}") {
                        session.run()
                    }
                }
            } catch (t: Throwable) {
                _status.value = WsServerStatus(
                    isRunning = false,
                    port = port,
                    localUrls = discoverLocalWsUrls(port),
                    connectedClients = 0,
                    lastError = t.localizedMessage ?: "Failed to bind port $port"
                )
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        _status.value = _status.value.copy(
            isRunning = false,
            connectedClients = 0
        )
    }

    fun broadcast(text: String) {
        clients.forEach { it.sendText(text) }
    }

    fun refreshNetworkInterfaces() {
        val current = _status.value
        _status.value = current.copy(localUrls = discoverLocalWsUrls(current.port))
    }

    private fun updateClientCount() {
        _status.value = _status.value.copy(
            connectedClients = clients.size,
            localUrls = discoverLocalWsUrls(_status.value.port)
        )
    }

    private inner class ClientSession(private val socket: Socket) {
        private val outLock = Any()
        private var out: OutputStream? = null

        fun run() {
            val clientAddr = socket.inetAddress?.hostAddress ?: "unknown"
            try {
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                out = output

                val key = performHandshake(input, output)
                if (key == null) {
                    close()
                    return
                }

                // Send initial welcome/status frame
                sendText(onMessageReceived(clientAddr, "__CONNECTED__"))

                while (running.get() && !socket.isClosed) {
                    val frame = readFrame(input) ?: break
                    when (frame.opcode) {
                        0x1 -> { // Text frame
                            val reply = onMessageReceived(clientAddr, frame.payloadText)
                            sendText(reply)
                        }
                        0x8 -> { // Close
                            sendControlFrame(0x8, ByteArray(0))
                            break
                        }
                        0x9 -> { // Ping -> Pong
                            sendControlFrame(0xA, frame.payloadBytes)
                        }
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
            } finally {
                clients.remove(this)
                updateClientCount()
                close()
            }
        }

        fun sendText(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            sendFrame(0x1, bytes)
        }

        private fun sendControlFrame(opcode: Int, payload: ByteArray) {
            sendFrame(opcode, payload)
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            val output = out ?: return
            synchronized(outLock) {
                try {
                    val header = ByteArrayOutputStream()
                    header.write(0x80 or (opcode and 0x0F))
                    when {
                        payload.size <= 125 -> header.write(payload.size)
                        payload.size <= 65535 -> {
                            header.write(126)
                            header.write((payload.size ushr 8) and 0xFF)
                            header.write(payload.size and 0xFF)
                        }
                        else -> {
                            header.write(127)
                            val len = payload.size.toLong()
                            for (shift in 56 downTo 0 step 8) {
                                header.write(((len ushr shift) and 0xFF).toInt())
                            }
                        }
                    }
                    output.write(header.toByteArray())
                    output.write(payload)
                    output.flush()
                } catch (_: Exception) {
                    close()
                }
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class WsFrame(val opcode: Int, val payloadBytes: ByteArray) {
        val payloadText: String get() = String(payloadBytes, Charsets.UTF_8)
    }

    private fun performHandshake(input: BufferedInputStream, output: OutputStream): String? {
        val headers = mutableMapOf<String, String>()
        val lineBuf = ByteArrayOutputStream()
        var firstLine = true

        while (true) {
            lineBuf.reset()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (prev == '\r'.code && b == '\n'.code) break
                if (b != '\r'.code) lineBuf.write(b)
                prev = b
            }
            val line = lineBuf.toString("UTF-8")
            if (line.isEmpty()) break
            if (firstLine) {
                firstLine = false
            } else {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                }
            }
        }

        val wsKey = headers["sec-websocket-key"] ?: return null
        val acceptRaw = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(acceptRaw.toByteArray(Charsets.UTF_8))
        val accept = Base64.encodeToString(sha1, Base64.NO_WRAP)

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
        return wsKey
    }

    private fun readFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()

        if (length == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi == -1 || lo == -1) return null
            length = ((hi shl 8) or lo).toLong()
        } else if (length == 127L) {
            var len = 0L
            for (i in 0 until 8) {
                val b = input.read()
                if (b == -1) return null
                len = (len shl 8) or (b.toLong() and 0xFFL)
            }
            length = len
        }

        if (length > 1_048_576L) return null // 1MB safety cap

        val maskKey = ByteArray(4)
        if (masked) {
            readFully(input, maskKey)
        }

        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(opcode, payload)
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var offset = 0
        while (offset < dest.size) {
            val r = input.read(dest, offset, dest.size - offset)
            if (r == -1) throw EOFException("Unexpected EOF in WebSocket frame")
            offset += r
        }
    }

    companion object {
        fun discoverLocalWsUrls(port: Int): List<String> {
            val urls = mutableListOf<String>()
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in Collections.list(nif.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { urls.add("ws://$it:$port") }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (urls.isEmpty()) {
                urls.add("ws://127.0.0.1:$port")
            }
            return urls.distinct()
        }
    }
}
