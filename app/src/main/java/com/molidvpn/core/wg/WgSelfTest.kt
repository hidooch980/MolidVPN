package com.molidvpn.core.wg

import java.util.Base64

/**
 * Known-answer and round-trip checks for the tunnel core. Runs on the device,
 * without a server: RFC vectors for the primitives and a complete handshake
 * between two local peers with traffic flowing in both directions.
 */
object WgSelfTest {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    fun run(): List<Check> {
        val out = ArrayList<Check>()

        // 1. BLAKE2s, RFC 7693
        val expected = "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982"
        val got = toHex(WgCrypto.hash("abc".toByteArray()))
        out.add(Check("BLAKE2s-256 (RFC 7693)", got == expected, got.take(16) + "…"))

        // 2. Keyed BLAKE2s and HMAC, the two modes MAC1 and the KDF rely on
        val key = ByteArray(32) { it.toByte() }
        val block = ByteArray(64) { it.toByte() }
        val keyed = toHex(WgCrypto.mac(key, block))
        out.add(
            Check(
                "BLAKE2s کلید‌دار ۱۲۸ بیتی",
                keyed == "f1efa90d2547036841ecd3627fafbc36",
                keyed.take(12) + "…"
            )
        )

        val hmac = toHex(WgCrypto.hmac(key, "wireguard".toByteArray()))
        out.add(
            Check(
                "HMAC-BLAKE2s (KDF)",
                hmac == "856f1b248b4d5a741767b3242d3d7a3474e4c3ff1681af8bdd10d7fb6b428d1f",
                hmac.take(12) + "…"
            )
        )

        val kdf = WgCrypto.kdf(2, key, "input".toByteArray())
        val kdfOk = toHex(kdf[0]) == "87b7f70b24183c6db5dc4f6d9f4c47cdb203321aa2a0bef714896adb3107a040" &&
            toHex(kdf[1]) == "538a02e1eb2e31b7e4d4b82476d1fe2acf50085d3fb584537279195f5d1f5824"
        out.add(Check("KDF دو خروجی", kdfOk, toHex(kdf[1]).take(12) + "…"))

        // 3. X25519, RFC 7748 §6.1
        val aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val aPub = toHex(WgCrypto.publicKey(aPriv))
        val okPub = aPub == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        out.add(Check("X25519 کلید عمومی (RFC 7748)", okPub, aPub.take(12) + "…"))

        val bPub = WgCrypto.publicKey(bPriv)
        val shared = WgCrypto.dh(aPriv, bPub)
        val shared2 = WgCrypto.dh(bPriv, WgCrypto.publicKey(aPriv))
        val okShared = shared != null && shared2 != null &&
            toHex(shared) == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742" &&
            shared.contentEquals(shared2)
        out.add(Check("X25519 اشتراک راز (RFC 7748)", okShared, (shared?.let { toHex(it).take(12) } ?: "—") + "…"))

        // 4. ChaCha20-Poly1305 round trip and tamper detection
        val plain = "MolidVPN tunnel payload".toByteArray()
        val sealed = WgCrypto.seal(key, 1L, plain, ByteArray(0))
        val opened = WgCrypto.open(key, 1L, sealed, ByteArray(0))
        val tampered = sealed.copyOf()
        tampered[3] = (tampered[3].toInt() xor 1).toByte()
        val rejected = WgCrypto.open(key, 1L, tampered, ByteArray(0)) == null
        out.add(
            Check(
                "ChaCha20-Poly1305",
                opened != null && opened.contentEquals(plain) && rejected && sealed.size == plain.size + 16,
                "برچسب " + sealed.size + " بایت، دست‌کاری رد شد"
            )
        )

        // 4b. HChaCha20 known answer (draft-irtf-cfrg-xchacha) and XChaCha20-Poly1305 round trip, used by cookie replies
        val hkey = ByteArray(32) { it.toByte() }
        val hnonce = hex("000000090000004a0000000031415927")
        val hout = toHex(WgCrypto.hchacha20(hkey, hnonce))
        out.add(
            Check(
                "HChaCha20 (XChaCha)",
                hout == "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
                hout.take(12) + "…"
            )
        )
        val xct = WgCrypto.xseal(hkey, hnonce + ByteArray(8), "cookie".toByteArray(), ByteArray(16))
        val xpt = WgCrypto.xopen(hkey, hnonce + ByteArray(8), xct, ByteArray(16))
        out.add(Check("XChaCha20-Poly1305 رفت‌وبرگشت", xpt != null && String(xpt) == "cookie", ""))

        // 5. Complete handshake between two local peers
        val t0 = System.currentTimeMillis()
        val peerA = WgCrypto.keyPair()
        val peerB = WgCrypto.keyPair()
        val initiator = WgHandshake(peerA.first, peerB.second)
        val responder = WgHandshake(peerB.first, peerA.second)

        val msg1 = initiator.createInitiation()
        val ok1 = msg1.size == WgHandshake.MSG_INITIATION && responder.consumeInitiation(msg1)
        val msg2 = if (ok1) responder.createResponse() else ByteArray(0)
        val ok2 = ok1 && msg2.size == WgHandshake.MSG_RESPONSE && initiator.consumeResponse(msg2)
        val stage = when {
            !ok1 -> "initiation"
            !ok2 -> "response"
            else -> "ok"
        }
        val elapsed = System.currentTimeMillis() - t0
        out.add(
            Check(
                "دست‌دادن Noise IKpsk2",
                ok2,
                if (ok2) "۱۴۸ + ۹۲ بایت در " + elapsed + " ms"
                else "توقف در " + stage + " (" + (if (ok1) initiator.lastError else responder.lastError) + ")"
            )
        )

        if (ok2) {
            val sa = WgSession(initiator.session())
            val sb = WgSession(responder.session())
            val forward = sa.seal("سلام از مولد".toByteArray())
            val gotF = sb.open(forward)
            val backward = sb.seal("پاسخ سرور".toByteArray())
            val gotB = sa.open(backward)
            val replay = sb.open(forward) == null
            val okTransport = gotF != null && gotB != null &&
                String(gotF) == "سلام از مولد" && String(gotB) == "پاسخ سرور" && replay
            out.add(Check("انتقال داده و رد بازپخش", okTransport, "کلید متقارن، شمارنده ۶۴ بیتی"))

            val ka = sa.keepalive()
            val openedKa = sb.open(ka)
            out.add(Check("بستهٔ نگه‌دارنده", openedKa != null && openedKa.isEmpty(), "۱۶ + ۱۶ بایت"))
        }

        // 6. Base64 keys survive a config round trip
        val pub = Base64.getEncoder().encodeToString(peerA.second)
        val decoded = WgConfig.decodeKey(pub, "PublicKey")
        out.add(Check("کلید Base64 پیکربندی", decoded.contentEquals(peerA.second), pub.take(10) + "…"))

        return out
    }
}
