package com.molidvpn.core.wg

import java.util.concurrent.atomic.AtomicLong

/** Encrypts and authenticates transport packets for one established session. */
class WgSession(private val keys: WgHandshake.Session) {

    /** Seal runs on several threads (pump, timer, receiver); a plain counter could reuse a nonce. */
    private val sendCounter = AtomicLong(0L)
    private var recvWindow = -1L
    private var recvBitmap = 0L

    val remoteIndex: Int get() = keys.remoteIndex
    val localIndex: Int get() = keys.localIndex

    fun seal(packet: ByteArray): ByteArray {
        val counter = sendCounter.getAndIncrement()
        val body = WgCrypto.seal(keys.sendKey, counter, packet, WgCrypto.EMPTY)
        val msg = ByteArray(16 + body.size)
        msg[0] = 4
        WgCrypto.writeU32(msg, 4, keys.remoteIndex)
        WgCrypto.writeU64(msg, 8, counter)
        System.arraycopy(body, 0, msg, 16, body.size)
        return msg
    }

    fun keepalive(): ByteArray = seal(WgCrypto.EMPTY)

    fun open(msg: ByteArray): ByteArray? {
        if (msg.size < 32 || msg[0].toInt() != 4) return null
        if (WgCrypto.readU32(msg, 4) != keys.localIndex) return null
        val counter = WgCrypto.readU64(msg, 8)
        if (!replayFresh(counter)) return null
        val body = msg.copyOfRange(16, msg.size)
        val plain = WgCrypto.open(keys.receiveKey, counter, body, WgCrypto.EMPTY) ?: return null
        // Only an authenticated packet may move the window; unauthenticated counters must not.
        markSeen(counter)
        return plain
    }

    /** 64 packet sliding replay window: checks without changing state. */
    private fun replayFresh(counter: Long): Boolean {
        if (recvWindow < 0 || counter > recvWindow) return true
        val diff = recvWindow - counter
        if (diff >= 64) return false
        return recvBitmap and (1L shl diff.toInt()) == 0L
    }

    private fun markSeen(counter: Long) {
        if (recvWindow < 0) {
            recvWindow = counter
            recvBitmap = 1L
        } else if (counter > recvWindow) {
            val shift = counter - recvWindow
            recvBitmap = if (shift >= 64) 1L else (recvBitmap shl shift.toInt()) or 1L
            recvWindow = counter
        } else {
            recvBitmap = recvBitmap or (1L shl (recvWindow - counter).toInt())
        }
    }
}
