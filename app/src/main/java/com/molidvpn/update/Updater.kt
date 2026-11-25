package com.molidvpn.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.molidvpn.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * What the last manifest check found, so the home screen can say it out loud
 * instead of leaving the news in a three second toast.
 */
object UpdateState {
    var version by mutableStateOf<String?>(null)
        private set
    var code by mutableIntStateOf(0)
        private set
    /** -1 idle, 0..100 while the package is downloading. */
    var progress by mutableIntStateOf(-1)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Last download failure, shown in the banner until the next attempt. */
    var error by mutableStateOf<String?>(null)
        private set

    internal fun found(versionName: String, versionCode: Int) {
        version = versionName
        code = versionCode
        error = null
    }

    internal fun checking(v: Boolean) {
        checking = v
    }

    internal fun progress(v: Int) {
        progress = v
    }

    /** Clears the old failure message so the button reads clean again. */
    internal fun retry() {
        error = null
    }

    internal fun fail(message: String) {
        progress = -1
        error = message
    }

    internal fun clear() {
        version = null
        code = 0
        progress = -1
        error = null
    }
}

/**
 * Smart update: reads a small manifest, compares versionCode, then downloads the
 * APK, checks its SHA-256 *and* that it carries the same certificate as the
 * installed app before handing it to the installer.
 *
 * Every mirror is asked at once and the newest manifest wins. Asking only the
 * first reachable mirror was wrong: the CDNs pin a branch for hours, so a
 * stale copy answered "you are up to date" long after a release.
 */
object Updater {
    private val MANIFESTS = listOf(
        "https://raw.githubusercontent.com/hidooch980/MolidVPN/main/release/update.json",
        "https://cdn.jsdelivr.net/gh/hidooch980/MolidVPN@main/release/update.json",
        "https://raw.githack.com/hidooch980/MolidVPN/main/release/update.json",
        "https://github.com/hidooch980/MolidVPN/raw/main/release/update.json",
        "https://cdn.statically.io/gh/hidooch980/MolidVPN/main/release/update.json",
    )

    const val RELEASE_PAGE = "https://github.com/hidooch980/MolidVPN/releases/latest"

    @Volatile
    private var pending: JSONObject? = null

    /** Quiet check: publishes [UpdateState] and never downloads by itself. */
    fun probe(ctx: Context, onResult: (String) -> Unit = {}) {
        Thread {
            UpdateState.checking(true)
            try {
                val json = newestManifest()
                val code = json.optInt("versionCode", 0)
                val version = json.optString("versionName", code.toString())
                if (code <= BuildConfig.VERSION_CODE) {
                    UpdateState.clear()
                    pending = null
                    post { onResult("نسخهٔ " + BuildConfig.VERSION_NAME + " تازه‌ترین نسخه است") }
                } else {
                    pending = json
                    UpdateState.found(version, code)
                    post { onResult("نسخهٔ " + version + " آماده است") }
                }
            } catch (e: Exception) {
                post { onResult("بررسی بروزرسانی ناموفق بود؛ از صفحهٔ انتشار دانلود کن") }
            } finally {
                UpdateState.checking(false)
            }
        }.start()
    }

    /** Downloads the announced release, verifies it and opens the installer. */
    fun install(ctx: Context, onResult: (String) -> Unit) {
        Thread {
            val json = pending
            if (json == null) {
                post { onResult("اول نسخه را بررسی کن") }
                return@Thread
            }
            val version = json.optString("versionName", "")
            val expected = json.optString("sha256", "").lowercase()
            val sources = mirrors(json)
            val apk = File(ctx.cacheDir, "molid-update.apk")
            var downloaded = false
            // The package counts as downloaded only when its bytes match the
            // manifest hash: a CDN that truncates the file must not pass.
            for (link in sources) {
                try {
                    if (apk.exists()) apk.delete()
                    UpdateState.progress(0)
                    http(link, apk) { p -> UpdateState.progress(p) }
                    if (apk.length() > 1_000_000L &&
                        (expected.isEmpty() || sha256(apk) == expected)
                    ) {
                        downloaded = true
                        break
                    }
                } catch (e: Exception) {
                    // truncated or dead mirror: try the next one
                }
            }
            if (!downloaded) {
                UpdateState.fail("دانلود ناموفق بود؛ دوباره بزن")
                post { onResult("دانلود نسخهٔ " + version + " ناموفق بود") }
                return@Thread
            }
            if (signer(ctx, null) == null || signer(ctx, null) != signer(ctx, apk)) {
                apk.delete()
                UpdateState.fail("امضای بسته با این نسخه نمی‌خواند")
                post { onResult("امضای بستهٔ جدید با این نسخه نمی‌خواند") }
                return@Thread
            }
            if (!canInstall(ctx)) {
                UpdateState.fail("اجازهٔ نصب بسته را روشن کن")
                post { onResult("اجازهٔ نصب بسته را روشن کن و دوباره بزن") }
                post { allowInstall(ctx) }
                return@Thread
            }
            post { onResult("نسخهٔ " + version + " آمادهٔ نصب است") }
            post { install(ctx, apk) }
        }.start()
    }

    /** Manual check from settings: says what is available and leaves the rest to the banner. */
    fun check(ctx: Context, onResult: (String) -> Unit) {
        probe(ctx, onResult)
    }

    /**
     * Every mirror is asked in parallel with a short deadline; the manifest with
     * the highest versionCode wins, so one stale CDN cannot hide a release.
     */
    private fun newestManifest(): JSONObject {
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, JSONObject>>())
        val bust = "?t=" + System.currentTimeMillis()
        val threads = MANIFESTS.map { link ->
            Thread {
                try {
                    results.add(link to JSONObject(http(link + bust)))
                } catch (_: Exception) {
                    // another mirror may answer
                }
            }.also { it.isDaemon = true; it.start() }
        }
        val deadline = System.currentTimeMillis() + 9_000
        for (t in threads) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            t.join(left)
        }
        // raw.githubusercontent has the shortest cache, so it is the truth when
        // it answers; a CDN only decides when the source itself is unreachable.
        val authoritative = results.filter { (link, _) -> link.contains("raw.githubusercontent.com") || link.contains("github.com/hidooch980") }
        val pool = authoritative.ifEmpty { results }
        return pool.maxByOrNull { (_, json) -> json.optInt("versionCode", 0) }?.second
            ?: throw IllegalStateException("no manifest")
    }

    /**
     * Download links in order: the official release asset first, then the CDN
     * mirrors. The free CDNs cap file size (jsDelivr refuses anything above
     * 20 MB), so an 80 MB package died halfway when they were tried first.
     */
    private fun mirrors(json: JSONObject): List<String> {
        val out = ArrayList<String>()
        val main = json.optString("url")
        if (main.isNotBlank()) out.add(main)
        val array = json.optJSONArray("mirrors")
        if (array != null) {
            for (i in 0 until array.length()) {
                val link = array.optString(i)
                if (link.isNotBlank()) out.add(link)
            }
        }
        return out
    }

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    private fun allowInstall(ctx: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    private fun install(ctx: Context, apk: File) {
        val authority = ctx.packageName + ".updates"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "MolidVPN/" + BuildConfig.VERSION_NAME)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun http(url: String, out: File, onProgress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MolidVPN/" + BuildConfig.VERSION_NAME)
        val expectedBytes = c.contentLengthLong
        val total = expectedBytes.coerceAtLeast(1L)
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    done += n
                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                    if (pct != last) {
                        last = pct
                        UpdateState.progress(pct)
                        onProgress(pct)
                    }
                }
                // A proxy that drops the stream halfway must not look complete.
                if (expectedBytes > 0 && done != expectedBytes) {
                    throw IllegalStateException("truncated download")
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun signer(ctx: Context, apk: File?): String? {
        val pm = ctx.packageManager
        return try {
            val info = if (apk == null) {
                if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                else pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            } else {
                if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                else pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return null
            val bytes = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                info.signatures?.firstOrNull()?.toByteArray()
            } ?: return null
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun post(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
