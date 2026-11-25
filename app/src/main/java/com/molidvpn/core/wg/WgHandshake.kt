package com.molidvpn.core.wg

/**
 * The Noise IKpsk2 handshake exactly as the WireGuard whitepaper lays it out.
 * Both roles live here: the app uses the initiator half, the engine self-test
 * uses the responder half to complete a handshake without a server.
 */
class WgHandshake(val localPrivate: ByteArray, val peerPublic: ByteArray) {

    companion object {
        const val MSG_INITIATION = 148
        const val MSG_RESPONSE = 92
        const val MSG_COOKIE = 64
        const val MSG_TRANSPORT = 4
        const val TYPE_INITIATION = 1
        const val TYPE_RESPONSE = 2
        const val TYPE_COOKIE = 3
        const val TYPE_TRANSPORT = 4

        private val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray()
        private val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray()
        private val LABEL_MAC1 = "mac1----".toByteArray()
        private val LABEL_COOKIE = "cookie--".toByteArray()

        fun mac1Key(publicKey: ByteArray): ByteArray = WgCrypto.hash(LABEL_MAC1, publicKey)
    }

    val localPublic: ByteArray = WgCrypto.publicKey(localPrivate)
    private val staticStatic: ByteArray = WgCrypto.dh(localPrivate, peerPublic)
        ?: throw IllegalArgumentException("peer key is not a valid curve point")

    var hash: ByteArray = WgCrypto.hash(WgCrypto.hash(CONSTRUCTION), IDENTIFIER)
        private set
    var chainKey: ByteArray = WgCrypto.hash(CONSTRUCTION)
        private set

    var localIndex: Int = 0
    var remoteIndex: Int = 0
    var isInitiator: Boolean = false
        private set
    var finished: Boolean = false
        private set
    /** Which step of the last consumed message failed, for diagnostics. */
    var lastError: String = ""
        private set

    /** WireGuard always runs with a 32 byte preshared key; zero means "not set". */
    private val presharedKey = ByteArray(32)

    private var ephemeralPrivate: ByteArray? = null
    private var remoteEphemeral: ByteArray? = null

    /** Two transport keys plus the indices the peer expects. */
    class Session(
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val localIndex: Int,
        val remoteIndex: Int,
    )

    private fun mixHash(vararg parts: ByteArray) {
        hash = WgCrypto.hash(hash, *parts)
    }

    private fun kdf2(input: ByteArray): ByteArray {
        val out = WgCrypto.kdf(2, chainKey, input)
        chainKey = out[0]
        return out[1]
    }

    private fun kdf1(input: ByteArray) {
        chainKey = WgCrypto.kdf(1, chainKey, input)[0]
    }

    /** MAC1 of the last initiation we sent: the AAD that binds a cookie reply to it. */
    private var lastMac1 = ByteArray(16)

    /**
     * Builds the initiation. When the responder has sent a cookie (it is under
     * load), MAC2 is added so the packet is accepted before the expensive checks.
     */
    fun createInitiation(cookie: ByteArray? = null): ByteArray {
        isInitiator = true
        val e = WgCrypto.keyPair()
        ephemeralPrivate = e.first
        localIndex = WgCrypto.randomIndex()

        val msg = ByteArray(MSG_INITIATION)
        msg[0] = TYPE_INITIATION.toByte()
        WgCrypto.writeU32(msg, 4, localIndex)
        System.arraycopy(e.second, 0, msg, 8, 32)

        // The responder's static public key seeds the transcript on both sides.
        mixHash(peerPublic)
        mixHash(e.second)
        kdf1(e.second)

        // The static key travels under "es": our fresh ephemeral against their static.
        val es = WgCrypto.dh(e.first, peerPublic) ?: return ByteArray(0)
        val keyStatic = kdf2(es)
        val encStatic = WgCrypto.seal(keyStatic, 0, localPublic, hash)
        System.arraycopy(encStatic, 0, msg, 40, encStatic.size)
        mixHash(encStatic)

        // The timestamp travels under "ss", the precomputed static-static secret.
        val keyTs = kdf2(staticStatic)
        val encTs = WgCrypto.seal(keyTs, 0, WgCrypto.timestamp(), hash)
        System.arraycopy(encTs, 0, msg, 88, encTs.size)
        mixHash(encTs)

        val m1 = WgCrypto.mac(mac1Key(peerPublic), msg.copyOfRange(0, 116))
        System.arraycopy(m1, 0, msg, 116, 16)
        lastMac1 = m1
        if (cookie != null) {
            val m2 = WgCrypto.mac(cookie, msg.copyOfRange(0, 132))
            System.arraycopy(m2, 0, msg, 132, 16)
        }
        return msg
    }

    /**
     * Opens a cookie reply (type 3) for our last initiation. The cookie is
     * encrypted under a key derived from the responder's public key, with MAC1
     * as associated data, so only the real responder can produce a usable one.
     */
    fun openCookie(msg: ByteArray): ByteArray? {
        if (msg.size != MSG_COOKIE || msg[0].toInt() != TYPE_COOKIE) return null
        if (WgCrypto.readU32(msg, 4) != localIndex) return null
        val key = WgCrypto.hash(LABEL_COOKIE, peerPublic)
        return WgCrypto.xopen(key, msg.copyOfRange(8, 32), msg.copyOfRange(32, 64), lastMac1)
    }

    fun consumeInitiation(msg: ByteArray): Boolean {
        lastError = ""
        if (msg.size != MSG_INITIATION || msg[0].toInt() != TYPE_INITIATION) {
            lastError = "header"
            return false
        }
        isInitiator = false
        remoteIndex = WgCrypto.readU32(msg, 4)
        val eps = msg.copyOfRange(8, 40)
        remoteEphemeral = eps

        mixHash(localPublic)
        mixHash(eps)
        kdf1(eps)

        val es = WgCrypto.dh(localPrivate, eps)
        if (es == null) {
            lastError = "es"
            return false
        }
        val keyStatic = kdf2(es)
        val peer = WgCrypto.open(keyStatic, 0, msg.copyOfRange(40, 88), hash)
        if (peer == null) {
            lastError = "static"
            return false
        }
        if (!peer.contentEquals(peerPublic)) {
            lastError = "peer-key"
            return false
        }
        mixHash(msg.copyOfRange(40, 88))

        val keyTs = kdf2(staticStatic)
        if (WgCrypto.open(keyTs, 0, msg.copyOfRange(88, 116), hash) == null) {
            lastError = "timestamp"
            return false
        }
        mixHash(msg.copyOfRange(88, 116))

        val m1 = WgCrypto.mac(mac1Key(localPublic), msg.copyOfRange(0, 116))
        for (i in 0 until 16) if (m1[i] != msg[116 + i]) {
            lastError = "mac1"
            return false
        }
        return true
    }

    fun createResponse(): ByteArray {
        if (isInitiator) throw IllegalStateException("only the responder answers")
        val e = WgCrypto.keyPair()
        ephemeralPrivate = e.first
        localIndex = WgCrypto.randomIndex()

        val msg = ByteArray(MSG_RESPONSE)
        msg[0] = TYPE_RESPONSE.toByte()
        WgCrypto.writeU32(msg, 4, localIndex)
        WgCrypto.writeU32(msg, 8, remoteIndex)
        System.arraycopy(e.second, 0, msg, 12, 32)

        mixHash(e.second)
        kdf1(e.second)

        val ee = WgCrypto.dh(e.first, remoteEphemeral!!) ?: return ByteArray(0)
        kdf2(ee)

        // Noise "se": the responder's fresh ephemeral against the initiator's static.
        val se = WgCrypto.dh(e.first, peerPublic) ?: return ByteArray(0)
        kdf2(se)

        val psk = WgCrypto.kdf(3, chainKey, presharedKey)
        chainKey = psk[0]
        mixHash(psk[1])
        val empty = WgCrypto.seal(psk[2], 0, WgCrypto.EMPTY, hash)
        System.arraycopy(empty, 0, msg, 44, 16)
        mixHash(empty)

        val m1 = WgCrypto.mac(mac1Key(peerPublic), msg.copyOfRange(0, 60))
        System.arraycopy(m1, 0, msg, 60, 16)
        return msg
    }

    fun consumeResponse(msg: ByteArray): Boolean {
        lastError = ""
        if (msg.size != MSG_RESPONSE || msg[0].toInt() != TYPE_RESPONSE) {
            lastError = "header"
            return false
        }
        if (WgCrypto.readU32(msg, 8) != localIndex) {
            lastError = "index"
            return false
        }
        remoteIndex = WgCrypto.readU32(msg, 4)
        val eps = msg.copyOfRange(12, 44)

        mixHash(eps)
        kdf1(eps)

        val ee = WgCrypto.dh(ephemeralPrivate!!, eps)
        if (ee == null) {
            lastError = "ee"
            return false
        }
        kdf2(ee)

        val se = WgCrypto.dh(localPrivate, eps)
        if (se == null) {
            lastError = "se"
            return false
        }
        kdf2(se)

        val psk = WgCrypto.kdf(3, chainKey, presharedKey)
        chainKey = psk[0]
        mixHash(psk[1])
        if (WgCrypto.open(psk[2], 0, msg.copyOfRange(44, 60), hash) == null) {
            lastError = "psk"
            return false
        }
        mixHash(msg.copyOfRange(44, 60))

        val m1 = WgCrypto.mac(mac1Key(localPublic), msg.copyOfRange(0, 60))
        for (i in 0 until 16) if (m1[i] != msg[60 + i]) {
            lastError = "mac1"
            return false
        }
        return true
    }

    fun session(): Session {
        val keys = WgCrypto.kdf(2, chainKey, WgCrypto.EMPTY)
        finished = true
        return if (isInitiator) {
            Session(keys[0], keys[1], localIndex, remoteIndex)
        } else {
            Session(keys[1], keys[0], localIndex, remoteIndex)
        }
    }
}
