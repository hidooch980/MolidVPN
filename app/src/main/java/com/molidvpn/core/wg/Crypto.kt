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

    fun seal(key: ByteArray, counter: Long, plaintext: ByteArray, aad: ByteArray): ByteArray =
        aeadSeal(key, nonce(counter), plaintext, aad)

    fun open(key: ByteArray, counter: Long, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        aeadOpen(key, nonce(counter), ciphertext, aad)

    /** XChaCha20-Poly1305 with a 24 byte nonce, used for cookie replies. */
    fun xseal(key: ByteArray, nonce24: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        aeadSeal(hchacha20(key, nonce24), xnonce(nonce24), plaintext, aad)

    fun xopen(key: ByteArray, nonce24: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        aeadOpen(hchacha20(key, nonce24), xnonce(nonce24), ciphertext, aad)

    private fun xnonce(nonce24: ByteArray): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(nonce24, 16, n, 4, 8)
        return n
    }

    private fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        var n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += c.doFinal(out, n)
        return out
    }

    private fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(ciphertext.size))
            var n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            n += c.doFinal(out, n)
            out.copyOf(n)
        } catch (e: Exception) {
            null
        }
    }

    /** HChaCha20 (draft-irtf-cfrg-xchacha): derives the per-message ChaCha20 key. */
    fun hchacha20(key: ByteArray, nonce24: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
        for (i in 0 until 4) s[12 + i] = le32(nonce24, 4 * i)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        val words = intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15])
        for (i in words.indices) for (j in 0 until 4) {
            out[4 * i + j] = ((words[i] ushr (8 * j)) and 0xFF).toByte()
        }
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun le32(b: ByteArray, off: Int): Int = readU32(b, off)

    fun keyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32)
        X25519.generatePrivateKey(rng, priv)
        return priv to publicKey(priv)
    }

    fun publicKey(priv: ByteArray): ByteArray {
        // RFC 7748 clamps the scalar before the base-point multiplication.
        // BouncyCastle's scalarMultBase does not, and a WireGuard private key
        // can arrive unclamped (or only clamped by the peer's tool), so the
        // advertised public key would not match the key used for DH.
        val k = priv.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val out = ByteArray(32)
        X25519.scalarMultBase(k, 0, out, 0)
        return out
    }

    /** True when the private key already has the RFC 7748 clamp applied. */
    fun isClamped(priv: ByteArray): Boolean =
        (priv[0].toInt() and 7) == 0 && (priv[31].toInt() and 128) == 0 && (priv[31].toInt() and 64) != 0

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
