package com.opendex.receiver.core

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

sealed class TransportEvent {
    data class Listening(val port: Int) : TransportEvent()
    data class SenderConnected(val request: HandshakeRequest) : TransportEvent()
    data class FrameReceived(val nalUnit: ByteArray) : TransportEvent()
    data class SenderDisconnected(val reason: String) : TransportEvent()
    data class Error(val message: String) : TransportEvent()
}

/**
 * Owns the listening socket and the per-frame read loop. Deliberately
 * transport-only: it has no idea what a MediaCodec is, so it can be unit
 * tested with plain sockets and reused for a future non-video channel.
 */
class TransportServer(
    private val port: Int = DEFAULT_PORT,
    private val scope: CoroutineScope,
    private val onEvent: (TransportEvent) -> Unit
) {
    companion object {
        const val DEFAULT_PORT = 43210
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024 // sanity cap, 8MB
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                onEvent(TransportEvent.Listening(port))

                while (running) {
                    val client = server.accept() // blocks until sender connects
                    clientSocket = client
                    handleClient(client)
                    // loop back and accept the next sender after disconnect
                }
            } catch (e: Exception) {
                if (running) onEvent(TransportEvent.Error(e.message ?: "transport error"))
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true // latency over throughput — critical for DeX input feel

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val handshakeLine = reader.readLine()
                ?: throw IllegalStateException("sender closed before handshake")

            val request = Gson().fromJson(handshakeLine, HandshakeRequest::class.java)
            onEvent(TransportEvent.SenderConnected(request))

            // Handshake acceptance/response is written by the caller (ReceiverService)
            // via writeHandshakeResponse() before frames start flowing.

            val dataIn = DataInputStream(client.getInputStream())
            while (running && !client.isClosed) {
                val length = dataIn.readInt()
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    throw IllegalStateException("invalid frame length: $length")
                }
                val buffer = ByteArray(length)
                dataIn.readFully(buffer)
                onEvent(TransportEvent.FrameReceived(buffer))
            }
        } catch (e: Exception) {
            onEvent(TransportEvent.SenderDisconnected(e.message ?: "disconnected"))
        } finally {
            runCatching { client.close() }
        }
    }

    /** Must be called right after SenderConnected, before frames arrive. */
    fun writeHandshakeResponse(response: HandshakeResponse) {
        val socket = clientSocket ?: return
        runCatching {
            val json = Gson().toJson(response) + "\n"
            socket.getOutputStream().write(json.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }
    }

    fun stop() {
        running = false
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
    }
}
