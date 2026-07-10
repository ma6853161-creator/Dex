package com.opendex.receiver.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.opendex.receiver.core.HandshakeResponse
import com.opendex.receiver.core.TransportEvent
import com.opendex.receiver.core.TransportServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ReceiverState {
    data object Idle : ReceiverState()
    data class Listening(val port: Int) : ReceiverState()
    data class Connected(val deviceName: String, val width: Int, val height: Int) : ReceiverState()
    data class Failed(val message: String) : ReceiverState()
}

/**
 * "Screen In" receiver mode. Bound + foreground service: bound so MainActivity
 * can hand it the render Surface directly (no cross-process IPC needed, since
 * decode happens in-process); foreground so Android doesn't kill the socket
 * while the tablet screen is off or another app is focused.
 */
class ReceiverService : Service() {

    private val binder = LocalBinder()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    private var transport: TransportServer? = null
    private var decoder: VideoDecoder? = null
    private var pendingSurface: Surface? = null

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state

    inner class LocalBinder : Binder() {
        fun getService(): ReceiverService = this@ReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    fun attachSurface(surface: Surface) {
        pendingSurface = surface
    }

    fun startListening(port: Int = TransportServer.DEFAULT_PORT) {
        if (transport != null) return

        transport = TransportServer(port = port, scope = scope) { event ->
            handleTransportEvent(event)
        }.also { it.start() }
    }

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.Listening -> {
                _state.value = ReceiverState.Listening(event.port)
            }

            is TransportEvent.SenderConnected -> {
                val surface = pendingSurface
                if (surface == null) {
                    transport?.writeHandshakeResponse(
                        HandshakeResponse(accepted = false, reason = "receiver surface not ready")
                    )
                    return
                }

                decoder = VideoDecoder(surface) { err ->
                    _state.value = ReceiverState.Failed(err.message ?: "decoder error")
                }.also {
                    it.start(event.request.sourceWidth, event.request.sourceHeight)
                }

                transport?.writeHandshakeResponse(
                    HandshakeResponse(
                        accepted = true,
                        receiverWidth = event.request.sourceWidth,
                        receiverHeight = event.request.sourceHeight
                    )
                )
                _state.value = ReceiverState.Connected(
                    event.request.deviceName,
                    event.request.sourceWidth,
                    event.request.sourceHeight
                )
            }

            is TransportEvent.FrameReceived -> {
                decoder?.feed(event.nalUnit, System.nanoTime() / 1000)
            }

            is TransportEvent.SenderDisconnected -> {
                decoder?.stop()
                decoder = null
                _state.value = ReceiverState.Listening(TransportServer.DEFAULT_PORT)
            }

            is TransportEvent.Error -> {
                _state.value = ReceiverState.Failed(event.message)
            }
        }
    }

    fun stopListening() {
        decoder?.stop()
        decoder = null
        transport?.stop()
        transport = null
        _state.value = ReceiverState.Idle
    }

    private fun startForegroundWithNotification() {
        val channelId = "receiver_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Screen In", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen In active")
            .setContentText("Waiting for a device to mirror")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        stopListening()
        job.cancel()
        super.onDestroy()
    }
}
