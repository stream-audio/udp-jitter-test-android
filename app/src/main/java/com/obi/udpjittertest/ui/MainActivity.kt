package com.obi.udpjittertest.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.obi.udpjittertest.BuildConfig
import com.obi.udpjittertest.R
import com.obi.udpjittertest.service.JitterTestApplication
import com.obi.udpjittertest.service.UdpListenService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.*
import kotlin.concurrent.timerTask

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = JitterTestApplication.TAG
        private const val PERMISSION_ID: Int = 18616
        private const val DISPLAY_INTERVAL_MS: Long = 2000
    }

    private var mServiceBound: UdpListenService.LocalBinder? = null
    private var mDisplayTimer: Timer? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            mServiceBound = service as UdpListenService.LocalBinder?
            displayCurrentState()
            if (isRunning()) startDisplayThread()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            mServiceBound = null
            stopDisplayThread()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_start).setOnClickListener { onStartClicked() }

        requestPermissions()
    }

    override fun onStart() {
        super.onStart()

        Intent(this, UdpListenService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        mServiceBound = null  // just in case
    }

    private fun onStartClicked() {
        if (mServiceBound?.isRunning() == true) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        val addrStr = findViewById<EditText>(R.id.et_address).text.toString()

        val addr = parseAddr(addrStr) ?: return

        Intent(this, UdpListenService::class.java).also { intent ->
            intent.putExtra(UdpListenService.INTENT_ARG_URL, addr)
            intent.putExtra(
                UdpListenService.INTENT_ARG_TYPE,
                UdpListenService.CmdType.START
            )
            ContextCompat.startForegroundService(this, intent)
        }

        Log.d(TAG, "Starting service for addr: $addr")

        displayCurrentState(started = true)
        startDisplayThread()
    }

    private fun stopListening() {
        Intent(this, UdpListenService::class.java).also { intent ->
            intent.putExtra(
                UdpListenService.INTENT_ARG_TYPE,
                UdpListenService.CmdType.STOP
            )
            startService(intent)
        }

        displayCurrentState(started = false)
        stopDisplayThread()
    }

    private fun parseAddr(addrStr: String): SocketAddress? {
        val showErr = {
            Toast.makeText(applicationContext, R.string.toast_err_wrong_addr, Toast.LENGTH_LONG)
                .show()
            null
        }

        val splitAddr = addrStr.split(':')
        if (splitAddr.size != 2) {
            return showErr()
        }

        val (ipStr, portStr) = Pair(splitAddr[0], splitAddr[1])
        val port: Int
        try {
            port = portStr.toShort().toInt()
            if (port < 0) {
                throw NumberFormatException("Port must not be less than 0")
            }
        } catch (e: java.lang.NumberFormatException) {
            return showErr()
        }

        val ip: InetAddress
        try {
            ip = InetAddress.getByName(ipStr)
        } catch (e: UnknownHostException) {
            return showErr()
        }

        return InetSocketAddress(ip, port)
    }

    private fun isRunning(): Boolean {
        return mServiceBound?.isRunning() == true
    }

    private fun displayCurrentState(started: Boolean? = null) {
        val isStarted = started ?: isRunning()

        val btn = findViewById<Button>(R.id.btn_start)
        btn.text = getText(if (isStarted) R.string.btn_stop else R.string.btn_start)

        if (isStarted) {
            displayPacketSummary()
        } else {
            displayEmptyPacketSummary()
        }
    }

    private fun displayPacketSummary() {
        val service = mServiceBound ?: return displayEmptyPacketSummary()
        val summary = service.getDisplaySummary() ?: return displayEmptyPacketSummary()

        findViewById<TextView>(R.id.tv_test_result).text = summary.toString()
        Log.i(TAG, "summary: $summary")
    }

    private fun displayEmptyPacketSummary() {
        findViewById<TextView>(R.id.tv_test_result).text = getText(R.string.tv_no_results_yet)
    }

    private fun stopDisplayThread() {
        mDisplayTimer?.cancel()
        mDisplayTimer = null
    }

    private fun startDisplayThread() {
        stopDisplayThread()

        mDisplayTimer = Timer().apply {
            schedule(timerTask {
                runOnUiThread {
                    displayPacketSummary()
                }
            }, DISPLAY_INTERVAL_MS, DISPLAY_INTERVAL_MS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (BuildConfig.DEBUG && requestCode != PERMISSION_ID) {
            error("Assertion failed")
        }
        if (grantResults.any { it == PackageManager.PERMISSION_DENIED }) {
            finish()
        }
    }

    private fun requestPermissions() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    private fun requestPermissions(permissions: Array<String>) {
        val permissionsToRequest = ArrayList<String>()
        for (permission in permissions) {
            val isGranted = ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isEmpty()) return

        ActivityCompat.requestPermissions(
            this, permissionsToRequest.toTypedArray(),
            PERMISSION_ID
        )
    }

}
