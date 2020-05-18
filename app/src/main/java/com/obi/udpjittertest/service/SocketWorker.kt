package com.obi.udpjittertest.service

import android.util.Log
import java.net.*
import java.util.*
import kotlin.concurrent.thread

class SocketWorker(dstAddr: SocketAddress) {
    data class DisplayInfo(
        val avg: Long,
        val p80: Long,
        val p85: Long,
        val p90: Long,
        val p95: Long,
        val p98: Long,
        val p99: Long,
        val p995: Long,
        val p999: Long
    ) {
        override fun toString(): String {
            return "   avg: $avg\n" +
                    "   80th: $p80,   85th: $p85,\n" +
                    "   90th: $p90,   95th: $p95,\n" +
                    "   98th: $p98,   99th: $p99,\n" +
                    "   995th: $p995, 999th: $p999,\n"
        }
    }

    data class Summary(
        val intervals: DisplayInfo,
        val loss: Int,
        val all_pkt_qty: Int
    ) {
        override fun toString(): String {
            val lossPercent = if (all_pkt_qty > 0)
                "%.2f".format(loss.toDouble() / all_pkt_qty * 100)
            else
                "0"

            return "loss: $loss/$all_pkt_qty ($lossPercent %)\n" +
                    "intervals:\n$intervals"
        }
    }

    companion object {
        private const val TAG = JitterTestApplication.TAG
        private const val PKT_TYPE_DATA = 'd'.toByte()
        private const val PKT_TYPE_REPLAY = 'r'.toByte()
        private const val PKT_TYPE_LISTEN = 'l'.toByte()
        private const val PKT_TYPE_STOP = 's'.toByte()
        private const val QUEUE_LEN = 500
    }

    private val mDstAddr: SocketAddress = dstAddr
    private val mSocket: DatagramSocket = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
    private val mThread: Thread
    private var mPrevPacketNum = 0

    @Volatile
    private var mMissingPacketsQty = 0

    @Volatile
    private var mAllPacketsQty = 0

    private var mPrevPktTime: Long = 0
    private val mIntervals = ArrayDeque<Long>()

    init {
        mThread = thread(start = true) {
            threadLoop()
        }
    }

    // TODO: Rewrite into async way, currently it is kind of lame
    fun stop() {
        thread(start = true) {
            sendStopPacket()
        }.join()

        mSocket.close()
        mThread.join()
    }

    fun getSummary(): Summary {
        val intervals = getQueuesAsArrays()
        return Summary(
            intervals = calcDisplayInfo(intervals),
            loss = mMissingPacketsQty,
            all_pkt_qty = mAllPacketsQty
        )
    }

    private fun threadLoop() {
        sendListenPacket()

        try {
            val buf = ByteArray(65535)
            while (true) {
                val p = DatagramPacket(buf, buf.size, mDstAddr)
                mSocket.receive(p)
                newPacketReceived(p)
            }
        } catch (e: SocketException) {
            if (e.message?.contains("Socket closed") != true) {
                Log.w(TAG, "Exception in threadLoop", e)
            }
        }
    }

    private fun sendListenPacket() {
        Log.i(TAG, "Sending listen packet to $mDstAddr")

        val b = ByteArray(1)
        b[0] = PKT_TYPE_LISTEN

        val p = DatagramPacket(b, b.size, mDstAddr)
        mSocket.send(p)
    }

    private fun sendStopPacket() {
        Log.i(TAG, "Sending stop packet to $mDstAddr")

        val b = ByteArray(1)
        b[0] = PKT_TYPE_STOP

        val p = DatagramPacket(b, b.size, mDstAddr)
        mSocket.send(p)
    }

    private fun newPacketReceived(p: DatagramPacket) {
        val bytes = p.data
        val len = p.length
        if (len == 0) {
            Log.w(TAG, "Received a packet with 0 length")
            return
        }

        when (val typeByte = bytes[0]) {
            PKT_TYPE_DATA -> newDataPacketReceived(bytes, len)
            else -> newUnknownDataPacketReceived(typeByte, bytes, len)
        }
    }

    private fun newDataPacketReceived(bytes: ByteArray, len: Int) {
        sendReplayPacket(bytes, len)

        val curTimeMs = System.currentTimeMillis()

        val num = intFromBeBytes(bytes, 1, len)

        if (num <= mPrevPacketNum) {
            Log.w(
                TAG,
                "Packet $num is received out of order. Current packet number is: $mPrevPacketNum"
            )
            return
        }

        if (mPrevPacketNum != 0) {
            for (n in mPrevPacketNum + 1 until num) {
                ++mMissingPacketsQty
                ++mAllPacketsQty
                Log.w(
                    TAG, "Packet $n is missing. Number of missing packets: $mMissingPacketsQty. " +
                            "Missing rate: ${mMissingPacketsQty.toDouble() / mAllPacketsQty}"
                )
            }
        }
        mPrevPacketNum = num
        ++mAllPacketsQty

        addNewEventToQueues(curTimeMs = curTimeMs, pktTimeMs = longFromBeBytes(bytes, 5, len))
    }

    private fun sendReplayPacket(b: ByteArray, len: Int) {
        b[0] = PKT_TYPE_REPLAY
        val p = DatagramPacket(b, len, mDstAddr)
        mSocket.send(p)
    }

    @Synchronized
    private fun addNewEventToQueues(curTimeMs: Long, pktTimeMs: Long) {
        while (mIntervals.size >= QUEUE_LEN) {
            mIntervals.pollFirst()
        }
        if (mPrevPktTime != 0L) {
            mIntervals.addLast(curTimeMs - mPrevPktTime)
        }
        mPrevPktTime = curTimeMs
    }

    private fun newUnknownDataPacketReceived(t: Byte, bytes: ByteArray, len: Int) {
        Log.w(TAG, "Got packet with unknown type: $t. Length: $len")
    }

    @Synchronized
    private fun getQueuesAsArrays(): Array<Long> {
        return mIntervals.toArray(emptyArray<Long>())
    }

    private fun calcDisplayInfo(arr: Array<Long>): DisplayInfo {
        if (arr.isEmpty()) {
            return DisplayInfo(
                avg = 0,
                p80 = 0,
                p85 = 0,
                p90 = 0,
                p95 = 0,
                p98 = 0,
                p99 = 0,
                p995 = 0,
                p999 = 0
            )
        }

        val avg = arr.average().toLong()
        arr.sort()
        val idx80 = (arr.size * 0.8).toInt()
        val idx85 = (arr.size * 0.85).toInt()
        val idx90 = (arr.size * 0.9).toInt()
        val idx95 = (arr.size * 0.95).toInt()
        val idx98 = (arr.size * 0.98).toInt()
        val idx99 = (arr.size * 0.99).toInt()
        val idx995 = (arr.size * 0.995).toInt()
        val idx999 = (arr.size * 0.999).toInt()

        return DisplayInfo(
            avg = avg,
            p80 = arr[idx80],
            p85 = arr[idx85],
            p90 = arr[idx90],
            p95 = arr[idx95],
            p98 = arr[idx98],
            p99 = arr[idx99],
            p995 = arr[idx995],
            p999 = arr[idx999]
        )
    }

    private fun intFromBeBytes(b: ByteArray, idx: Int, len: Int): Int {
        if (idx + 4 > len) {
            throw ArrayIndexOutOfBoundsException(
                "Expects at least 8 bytes starting at $idx position. Found: ${len - idx}"
            )
        }

        var res: Int = 0
        for (shift in 0 until 4) {
            res = res shl 8
            res = res or (b[idx + shift].toInt() and 0xFF)
        }

        return res
    }

    private fun longFromBeBytes(b: ByteArray, idx: Int, len: Int): Long {
        if (idx + 8 > len) {
            throw ArrayIndexOutOfBoundsException(
                "Expects at least 8 bytes starting at $idx position. Found: ${len - idx}"
            )
        }

        var res: Long = 0
        for (shift in 0 until 8) {
            res = res shl 8
            res = res or (b[idx + shift].toLong() and 0xFF)
        }

        return res
    }
}
