package com.molidvpn.core.free

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molidvpn.core.cores.SocksCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One public configuration that actually carried a probe, with its measured delay. */
data class FreeNode(val link: String, val country: String, val label: String, val latency: Int)

data class FreeCountry(val code: String, val count: Int, val latency: Int)

/**
 * Free-configuration pool: fetches public share lists, runs each candidate
 * through the bundled Xray core, and keeps only the ones whose SOCKS path
 * really answered. Nothing here is seeded or assumed.
 */
object FreePool {
    private const val TAG = "FreePool"
    private const val FILE = "free_pool.txt"
    private const val MAX_TEST = 24
    private const val FRESH_MS = 45 * 60_000L

    private val sources = listOf(
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/sub.txt",
    )

    var nodes by mutableStateOf<List<FreeNode>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var lastAt by mutableStateOf(0L)
        private set
    private var running = false

    fun best(code: String?): FreeNode? {
        val pool = nodes.filter { it.latency > 0 }
        if (pool.isEmpty()) return null
        if (code == null || code == "auto" || code.isEmpty()) return pool.minByOrNull { it.latency }
        return pool.filter { it.country == code }.minByOrNull { it.latency } ?: pool.minByOrNull { it.latency }
    }

    fun countries(): List<FreeCountry> =
        nodes.groupBy { it.country }
            .map { (code, list) -> FreeCountry(code, list.size, list.minOf { it.latency }) }
            .sortedBy { it.latency }

    fun load(ctx: Context) {
        if (nodes.isNotEmpty()) return
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        val parsed = runCatching {
            f.readLines().mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 3) null else {
                    val ms = parts[0].toIntOrNull() ?: return@mapNotNull null
                    FreeNode(parts[2], parts[1], labelOf(parts[2]), ms)
                }
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            nodes = parsed.sortedBy { it.latency }
            lastAt = f.lastModified()
            status = parsed.size.toString() + " سرور از حافظه"
        }
    }

    suspend fun ensure(ctx: Context, budgetMs: Long = 100_000, force: Boolean = false): List<FreeNode> =
        withContext(Dispatchers.IO) {
            load(ctx)
            if (!force && nodes.isNotEmpty() && System.currentTimeMillis() - lastAt < FRESH_MS) {
                return@withContext nodes
            }
            if (running) return@withContext nodes
            running = true
            busy = true
            try {
                status = "در حال گرفتن فهرست سرورهای عمومی…"
                val links = fetchAll()
                Log.d(TAG, "fetched " + links.size + " links")
                if (links.isEmpty()) {
                    status = "فهرست عمومی در دسترس نبود"
                    return@withContext nodes
                }
                status = "تست " + links.size + " کانفیگ با هستهٔ Xray…"
                val deadline = System.currentTimeMillis() + budgetMs
                val tested = testMany(ctx, links, deadline)
                Log.d(TAG, "working=" + tested.size)
                if (tested.isNotEmpty()) {
                    save(ctx, tested)
                    nodes = tested.sortedBy { it.latency }
                    lastAt = System.currentTimeMillis()
                    status = tested.size.toString() + " سرور سالم پیدا شد"
                } else {
                    status = "هیچ کانفیگ عمومی جواب نداد"
                }
                nodes
            } catch (e: Exception) {
                Log.w(TAG, "ensure failed", e)
                status = "خطا: " + (e.message ?: "نامشخص")
                nodes
            } finally {
                running = false
                busy = false
            }
        }

    private fun save(ctx: Context, list: List<FreeNode>) {
        runCatching {
            File(ctx.filesDir, FILE).writeText(
                list.joinToString("\n") { it.latency.toString() + "\t" + it.country + "\t" + it.link }
            )
        }
    }

    private fun fetchAll(): List<String> {
        val seen = LinkedHashSet<String>()
        for (url in sources) {
            val text = runCatching { download(url) }.getOrDefault("")
            if (text.isEmpty()) continue
            extract(text).forEach { if (seen.size < 600) seen.add(it) }
        }
        // Prefer configs whose remark names a country, and cap the test batch.
        val ranked = seen.sortedByDescending { countryOf(it) != "ZZ" }
        return ranked.take(MAX_TEST)
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MolidVPN/0.5")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return ""
            conn.inputStream.bufferedReader().use { it.readText().take(3_000_000) }
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(text: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""(vless|vmess|trojan|ss)://\S+""")
        for (m in re.findAll(text)) {
            val raw = m.value.trim().trimEnd(',', ';', ')', ']', '*', '"')
            if (raw.length in 24..4000) out.add(raw)
        }
        return out
    }

    private suspend fun testMany(ctx: Context, links: List<String>, deadline: Long): List<FreeNode> =
        coroutineScope {
            val queue = ConcurrentLinkedQueue(links)
            val out = java.util.Collections.synchronizedList(ArrayList<FreeNode>())
            (1..3).map {
                async(Dispatchers.IO) {
                    while (System.currentTimeMillis() < deadline) {
                        val link = queue.poll() ?: break
                        val ms = testLink(ctx, link)
                        if (ms != null) out.add(FreeNode(link, countryOf(link), labelOf(link), ms))
                    }
                }
            }.awaitAll()
            out.toList()
        }

    private fun testLink(ctx: Context, link: String): Int? {
        val latch = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val port = AtomicInteger(0)
        val core = SocksCore(ctx, SocksCore.Kind.XRAY, link, object : SocksCore.Listener {
            override fun onReady(socksPort: Int) {
                port.set(socksPort)
                ready.set(true)
                latch.countDown()
            }

            override fun onFailed(detail: String) {
                latch.countDown()
            }
        })
        if (!core.start()) return null
        return try {
            if (!latch.await(15, TimeUnit.SECONDS) || !ready.get()) null
            else SocksCore.probeMs(port.get(), 7_000)
        } finally {
            core.stop()
        }
    }

    private val names = mapOf(
        "US" to "آمریکا", "DE" to "آلمان", "NL" to "هلند", "FR" to "فرانسه", "GB" to "انگلیس",
        "CA" to "کانادا", "TR" to "ترکیه", "AE" to "امارات", "IR" to "ایران", "RU" to "روسیه",
        "FI" to "فینلاند", "SE" to "سوئد", "PL" to "لهستان", "AT" to "اتریش", "CH" to "سوئیس",
        "IT" to "ایتالیا", "ES" to "اسپانیا", "RO" to "رومانی", "CZ" to "چک", "SG" to "سنگاپور",
        "JP" to "ژاپن", "KR" to "کره", "HK" to "هنگ‌کنگ", "IN" to "هند", "AU" to "استرالیا",
        "BR" to "برزیل", "NO" to "نروژ", "DK" to "دانمارک", "BE" to "بلژیک", "IE" to "ایرلند",
        "LT" to "لیتوانی", "LV" to "لتونی", "EE" to "استونی", "UA" to "اوکراین", "KZ" to "قزاقستان",
    )

    fun nameOf(code: String): String = names[code] ?: if (code == "ZZ") "سایر" else code

    fun flagOf(code: String): String {
        if (code.length != 2 || code[0] !in 'A'..'Z' || code[1] !in 'A'..'Z') return "🌐"
        return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
    }

    fun countryOf(link: String): String {
        val frag = runCatching { URLDecoder.decode(link.substringAfter('#', ""), "UTF-8") }.getOrDefault("")
        val cps = frag.codePoints().toArray()
        for (i in 1 until cps.size) {
            val a = cps[i - 1]
            val b = cps[i]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                return "" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6))
            }
        }
        val hit = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").find(frag) ?: return "ZZ"
        val code = hit.groupValues[1]
        return if (names.containsKey(code)) code else "ZZ"
    }

    fun labelOf(link: String): String {
        val frag = runCatching { URLDecoder.decode(link.substringAfter('#', ""), "UTF-8") }.getOrDefault("")
        val clean = frag.replace(Regex("[^\\p{L}\\p{N} _.:-]"), " ").trim()
        return if (clean.isEmpty()) "سرور عمومی" else clean.take(46)
    }
}
