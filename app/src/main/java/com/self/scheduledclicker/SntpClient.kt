package com.self.scheduledclicker

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 輕量高精準度 SNTP (Simple Network Time Protocol) 客戶端。
 */
object SntpClient {
    private const val NTP_PORT = 123
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    private const val NTP_PACKET_SIZE = 48

    private const val ORIGINATE_TIME_OFFSET = 24
    private const val RECEIVE_TIME_OFFSET = 32
    private const val TRANSMIT_TIME_OFFSET = 40
    private const val OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L

    data class NtpResult(
        val ntpTimeMs: Long,
        val elapsedRealtimeMs: Long,
        val roundTripTimeMs: Long
    )

    fun syncTimeBestOf(host: String = "time.stdtime.gov.tw", samples: Int = 3, timeoutMs: Int = 2500): Result<NtpResult> {
        var bestResult: NtpResult? = null
        var lastException: Exception? = null

        repeat(samples) {
            try {
                val res = requestSingle(host, timeoutMs)
                if (bestResult == null || res.roundTripTimeMs < bestResult!!.roundTripTimeMs) {
                    bestResult = res
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        return if (bestResult != null) {
            Result.success(bestResult!!)
        } else {
            Result.failure(lastException ?: RuntimeException("NTP 校時失敗"))
        }
    }

    private fun requestSingle(host: String, timeoutMs: Int): NtpResult {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)
            val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)

            buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()

            val requestTime = System.currentTimeMillis()
            val requestTicks = SystemClock.elapsedRealtime()
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)

            socket.send(request)

            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val responseTicks = SystemClock.elapsedRealtime()
            val responseTime = requestTime + (responseTicks - requestTicks)

            val originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET)

            val roundTripTime = (responseTicks - requestTicks) - (transmitTime - receiveTime)
            val clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2

            val ntpTime = responseTime + clockOffset
            return NtpResult(
                ntpTimeMs = ntpTime,
                elapsedRealtimeMs = responseTicks,
                roundTripTimeMs = roundTripTime.coerceAtLeast(1L)
            )
        }
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        return ((seconds - OFFSET_1900_TO_1970) * 1000L) + ((fraction * 1000L) / 0x100000000L)
    }

    private fun writeTimeStamp(buffer: ByteArray, offset: Int, timeMs: Long) {
        if (timeMs == 0L) {
            buffer.fill(0, offset, offset + 8)
            return
        }
        val seconds = timeMs / 1000L + OFFSET_1900_TO_1970
        val milliseconds = timeMs % 1000L
        val fraction = (milliseconds * 0x100000000L) / 1000L

        write32(buffer, offset, seconds)
        write32(buffer, offset + 4, fraction)
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        return (b0.toLong() shl 24) or
                (b1.toLong() shl 16) or
                (b2.toLong() shl 8) or
                b3.toLong()
    }

    private fun write32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}
