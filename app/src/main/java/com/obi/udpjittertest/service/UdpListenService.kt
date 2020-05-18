package com.obi.udpjittertest.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.obi.udpjittertest.R
import com.obi.udpjittertest.ui.MainActivity
import java.net.*

class UdpListenService : Service() {
    inner class LocalBinder : Binder() {
        fun isRunning(): Boolean = mWorker != null
        fun getDisplaySummary(): SocketWorker.Summary? = mWorker?.getSummary()
    }
    internal enum class CmdType { START, STOP }

    companion object {
        const val INTENT_ARG_TYPE = "type" //<! CmdType
        const val INTENT_ARG_URL = "url" //<! SocketAddress

        private const val NOTIFICATION_ID: Int = 402
        private const val TAG = JitterTestApplication.TAG
    }

    private val mBinder = LocalBinder()
    private var mWorker: SocketWorker? = null

    override fun onBind(p0: Intent?): IBinder? {
        return mBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "UdpListenService.onStartCommand($intent)")

        if (intent == null) throw NullPointerException("Intent cannot be null")

        when (intent.getSerializableExtra(INTENT_ARG_TYPE) as CmdType) {
            CmdType.START -> start(intent)
            CmdType.STOP -> stop()
        }

        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (mWorker != null) return

        val dstAddr = intent.getSerializableExtra(INTENT_ARG_URL) as SocketAddress

        mWorker = SocketWorker(dstAddr)
        toForeground()
    }

    private fun stop() {
        if (mWorker == null) return

        mWorker?.stop()
        mWorker = null

        stopForegroundNotification()
    }

    private fun toForeground() {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, 0)
        }

        val notification = NotificationCompat.Builder(this, JitterTestApplication.CHANNEL_ID)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_text))
            .setSmallIcon(R.drawable.notification_small_icon)
            .setContentIntent(pendingIntent)
            .setTicker(getText(R.string.notification_ticker))
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundNotification() {
        val removeNotification = true
        stopForeground(removeNotification)
    }
}
