package com.molidvpn.core.wg

import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * The primitives WireGuard is built from: BLAKE2s, HMAC-BLAKE2s, X25519 and
 * ChaCha20-Poly1305. Nothing here is home-grown crypto; the heavy lifting is
 * done by BouncyCastle's vetted primitives.
 */
object WgCrypto {

    val rng = SecureRandom()
    val EMPTY = ByteArray(0)

    fun hash(vararg parts: ByteArray): ByteArray {
        val d = Blake2sDigest(256)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val d = Blake2sDigest(key, 16, null, null)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(16)
        d.doFinal(out, 0)
        return out
    }

    /** HMAC-BLAKE2s with a 64 byte block, as the WireGuard KDF expects. */
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val block = 64
        val k = if (key.size > block) hash(key) else key
        val ipad = ByteArray(block) { 0x36 }
        val opad = ByteArray(block) { 0x5c }
        for (i in k.indices) {
            ipad[i] = (ipad[i].toInt() xor k[i].toInt()).toByte()
            opad[i] = (opad[i].toInt() xor k[i].toInt()).toByte()
        }
        val inner = Blake2sDigest(256)
        inner.update(ipad, 0, block)
        for (p in parts) inner.update(p, 0, p.size)
        val innerOut = ByteArray(32)
        inner.doFinal(innerOut, 0)

        val outer = Blake2sDigest(256)
        outer.update(opad, 0, block)
        outer.update(innerOut, 0, 32)
        val out = ByteArray(32)
        outer.doFinal(out, 0)
        return out
    }

    /** WireGuard's KDF: returns n 32 byte outputs derived from key and input. */
    fun kdf(n: Int, key: ByteArray, input: ByteArray): List<ByteArray> {
        val tau = hmac(key, input)
        val out = ArrayList<ByteArray>(n)
        var prev = EMPTY
        for (i in 0 until n) {
            val part = hmac(tau, prev, byteArrayOf((i + 1).toByte()))
            out.add(part)
            prev = part
        }
        return out
    }

    fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        return n
    }

    fun seal(key: ByteArray, counter: Long, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        var n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += c.doFinal(out, n)
        return out
    }

    fun open(key: ByteArray, counter: Long, ciphertext: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce(counter), aad))
            val out = ByteArray(c.getOutputSize(ciphertext.size))
            var n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            n += c.doFinal(out, n)
            out.copyOf(n)
        } catch (e: Exception) {
            null
        }
    }

    fun keyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32)
        X25519.generatePrivateKey(rng, priv)
        return priv to publicKey(priv)
    }

    fun publicKey(priv: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519.scalarMultBase(priv, 0, out, 0)
        return out
    }

    fun dh(priv: ByteArray, pub: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) out else null
    }

    fun randomIndex(): Int = rng.nextInt()

    /** TAI64N timestamp: 8 byte seconds (2^62 + unix) followed by nanoseconds. */
    fun timestamp(): ByteArray {
        val out = ByteArray(12)
        val now = System.currentTimeMillis()
        val secs = 0x400000000000000AL + now / 1000
        for (i in 0 until 8) out[i] = ((secs ushr (8 * (7 - i))) and 0xFF).toByte()
        val nanos = ((now % 1000) * 1_000_000).toInt()
        for (i in 0 until 4) out[8 + i] = ((nanos ushr (8 * (3 - i))) and 0xFF).toByte()
        return out
    }

    fun readU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun writeU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
