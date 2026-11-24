package com.molidvpn.core.wg

/** Encrypts and authenticates transport packets for one established session. */
class WgSession(private val keys: WgHandshake.Session) {

    private var sendCounter = 0L
    private var recvWindow = -1L
    private var recvBitmap = 0L

    val remoteIndex: Int get() = keys.remoteIndex
    val localIndex: Int get() = keys.localIndex

    fun seal(packet: ByteArray): ByteArray {
        val counter = sendCounter++
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
        if (!accept(counter)) return null
        val body = msg.copyOfRange(16, msg.size)
        return WgCrypto.open(keys.receiveKey, counter, body, WgCrypto.EMPTY)
    }

    /** 64 packet sliding replay window. */
    private fun accept(counter: Long): Boolean {
        if (recvWindow < 0) {
            recvWindow = counter
            recvBitmap = 1L
            return true
        }
        if (counter > recvWindow) {
            val shift = counter - recvWindow
            recvBitmap = if (shift >= 64) 1L else (recvBitmap shl shift.toInt()) or 1L
            recvWindow = counter
            return true
        }
        val diff = recvWindow - counter
        if (diff >= 64) return false
        val bit = 1L shl diff.toInt()
        if (recvBitmap and bit != 0L) return false
        recvBitmap = recvBitmap or bit
        return true
    }
}
