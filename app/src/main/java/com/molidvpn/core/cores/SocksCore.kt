package com.molidvpn.core.cores

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A proxy core that runs as a bundled executable (`lib*.so` in jniLibs, extracted
 * by the installer) and exposes a local SOCKS5 port once it can carry traffic.
 *
 * Tor and Xray both run this way: their outbound sockets belong to this app,
 * which the TUN excludes, so they never loop back into the tunnel.
 */
class SocksCore(
    private val context: Context,
    private val kind: Kind,
    private val link: String?,
    private val listener: Listener,
) {
    enum class Kind { TOR, XRAY }

    interface Listener {
        fun onReady(socksPort: Int)
        fun onFailed(detail: String)
    }

    @Volatile private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile private var port = 0

    /** Starts the core; readiness is reported through [Listener]. */
    fun start(): Boolean {
        port = freePort()
        val cmd = try {
            when (kind) {
                Kind.TOR -> torCommand()
                Kind.XRAY -> xrayCommand()
            }
        } catch (e: Exception) {
            listener.onFailed(e.message ?: "پیکربندی هسته ساخته نشد")
            return false
        }
        if (cmd == null) {
            listener.onFailed("این لینک را هسته پشتیبانی نمی‌کند")
            return false
        }
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            listener.onFailed("اجرای " + kind.name + " ناموفق بود: " + e.message)
            return false
        }
        process = proc
        Thread({ readOutput(proc) }, "core-" + kind.name).apply { isDaemon = true }.start()
        if (kind == Kind.XRAY) {
            Thread({ waitForProbe() }, "xray-ready").apply { isDaemon = true }.start()
        }
        return true
    }

    fun stop() {
        stopped = true
        process?.destroy()
        process = null
    }

    private fun torCommand(): List<String> {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        val dataDir = File(context.filesDir, "tor").apply { mkdirs() }
        return listOf(
            exe.absolutePath,
            "--SocksPort", "127.0.0.1:$port",
            "--DataDirectory", dataDir.absolutePath,
            "--ignore-missing-torrc",
            "--Log", "notice stdout",
        )
    }

    private fun xrayCommand(): List<String>? {
        val config = XrayConfig.build(link ?: return null, port) ?: return null
        val exe = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        val dir = File(context.filesDir, "xray").apply { mkdirs() }
        val file = File(dir, "config.json").apply { writeText(config) }
        return listOf(exe.absolutePath, "run", "-c", file.absolutePath)
    }

    /** Tor reports readiness in its log; Xray is ready once a SOCKS request succeeds. */
    private fun readOutput(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                Log.d(TAG, line)
                if (kind == Kind.TOR && line.contains("Bootstrapped 100%")) {
                    listener.onReady(port)
                }
            }
        } catch (_: Exception) {
        }
        if (!stopped) listener.onFailed(kind.name + " متوقف شد")
    }

    private fun waitForProbe() {
        val deadline = System.currentTimeMillis() + 40_000
        while (!stopped && System.currentTimeMillis() < deadline) {
            if (probe(port)) {
                listener.onReady(port)
                return
            }
            Thread.sleep(1_000)
        }
        if (!stopped) listener.onFailed("هسته به اتصال نرسید")
    }

    companion object {
        private const val TAG = "SocksCore"

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Same SOCKS5 path as [probe], but returns the measured round-trip in
         * milliseconds so callers can rank configs by real delay.
         */
        fun probeMs(port: Int, timeoutMs: Int = 7_000): Int? {
            val t0 = System.nanoTime()
            return try {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(5, 1, 0))
                    out.flush()
                    val greet = ByteArray(2)
                    if (!readAll(inp, greet) || greet[1] != 0.toByte()) return null
                    val host = "connectivitycheck.gstatic.com".toByteArray()
                    out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80.toByte()))
                    out.flush()
                    val head = ByteArray(10)
                    if (!readAll(inp, head) || head[1] != 0.toByte()) return null
                    out.write("GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val status = inp.bufferedReader().readLine() ?: return null
                    if (!status.contains(" 204")) return null
                    ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceIn(1, timeoutMs)
                }
            } catch (_: Exception) {
                null
            }
        }


        /**
         * Reads the exit address through the tunnel itself (SOCKS5 + HTTP),
         * which is the only truthful source: the VPN app's own sockets bypass
         * its own tun, so a direct lookup would report the local line.
         */
        fun ipViaSocks(port: Int, timeoutMs: Int = 7_000): String? {
            return try {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(5, 1, 0))
                    out.flush()
                    val greet = ByteArray(2)
                    if (!readAll(inp, greet) || greet[1] != 0.toByte()) return null
                    val host = "api.ipify.org".toByteArray()
                    out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte(), *host, 0, 80.toByte()))
                    out.flush()
                    val head = ByteArray(10)
                    if (!readAll(inp, head) || head[1] != 0.toByte()) return null
                    out.write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nUser-Agent: MolidVPN\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val body = inp.bufferedReader().use { it.readText() }
                    val tail = body.substringAfter("\r\n\r\n", body)
                    val ip = tail.trim().split("\n").lastOrNull()?.trim().orEmpty()
                    if (ip.matches(Regex("^[0-9a-fA-F:.]{7,45}$"))) ip else null
                }
            } catch (_: Exception) {
                null
            }
        }
        /** SOCKS5 CONNECT to a public generate_204 endpoint; true when the path carries traffic. */
        fun probe(port: Int, timeoutMs: Int = 8_000): Boolean {
            return try {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(5, 1, 0))
                    out.flush()
                    val greet = ByteArray(2)
                    if (!readAll(inp, greet) || greet[1] != 0.toByte()) return false
                    val host = "connectivitycheck.gstatic.com".toByteArray()
                    val req = byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80.toByte())
                    out.write(req)
                    out.flush()
                    val head = ByteArray(10)
                    if (!readAll(inp, head) || head[1] != 0.toByte()) return false
                    val request = "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n"
                    out.write(request.toByteArray())
                    out.flush()
                    val status = inp.bufferedReader().readLine() ?: return false
                    status.contains(" 204")
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun readAll(input: java.io.InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }
    }
}
