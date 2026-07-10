package com.opendex.receiver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.opendex.receiver.R
import com.opendex.receiver.receiver.ReceiverService
import com.opendex.receiver.receiver.ReceiverState
import kotlinx.coroutines.launch

/**
 * Phase 1 UI: fullscreen render surface + a single status line. Deliberately
 * minimal — device picker, settings, clipboard/file-transfer UI etc. are
 * later phases per the roadmap.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }

    private var receiverService: ReceiverService? = null
    private lateinit var statusText: TextView
    private lateinit var surfaceView: SurfaceView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ReceiverService.LocalBinder).getService()
            receiverService = service
            observeState(service)
            if (surfaceView.holder.surface?.isValid == true) {
                service.attachSurface(surfaceView.holder.surface)
            }
            service.startListening()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        statusText = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            text = getString(R.string.status_idle)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                receiverService?.attachSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        bindService(
            Intent(this, ReceiverService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun observeState(service: ReceiverService) {
        lifecycleScope.launch {
            service.state.collect { state ->
                statusText.text = when (state) {
                    is ReceiverState.Idle -> getString(R.string.status_idle)
                    is ReceiverState.Listening -> getString(R.string.status_listening, state.port)
                    is ReceiverState.Connected -> getString(
                        R.string.status_connected,
                        "${state.deviceName} (${state.width}x${state.height})"
                    )
                    is ReceiverState.Failed -> "Error: ${state.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}
