package com.molidvpn.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.molidvpn.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Smart update: reads a small manifest, compares versionCode, then downloads the
 * APK, checks its SHA-256 *and* that it carries the same certificate as the
 * installed app before handing it to the installer.
 *
 * Every address below has a mirror first: Iranian networks routinely block
 * raw.githubusercontent.com and the GitHub asset host, while the jsDelivr CDN
 * usually stays reachable. The manifest itself may list further mirrors.
 */
object Updater {
    private val MANIFESTS = listOf(
        "https://raw.githack.com/hidooch980/MolidVPN/main/release/update.json",
        "https://cdn.statically.io/gh/hidooch980/MolidVPN/main/release/update.json",
        "https://cdn.jsdelivr.net/gh/hidooch980/MolidVPN@main/release/update.json",
        "https://raw.githubusercontent.com/hidooch980/MolidVPN/main/release/update.json",
    )

    const val RELEASE_PAGE = "https://github.com/hidooch980/MolidVPN/releases/latest"

    fun check(ctx: Context, onResult: (String) -> Unit) {
        Thread {
            try {
                val json = manifest()
                val code = json.optInt("versionCode", 0)
                if (code <= BuildConfig.VERSION_CODE) {
                    post { onResult("نسخهٔ " + BuildConfig.VERSION_NAME + " تازه‌ترین نسخه است") }
                    return@Thread
                }
                val version = json.optString("versionName", code.toString())
                val expected = json.optString("sha256", "").lowercase()
                val sources = mirrors(json)

                val apk = File(ctx.cacheDir, "molid-update.apk")
                var downloaded = false
                for (link in sources) {
                    try {
                        if (apk.exists()) apk.delete()
                        http(link, apk)
                        if (apk.length() > 1_000_000L) {
                            downloaded = true
                            break
                        }
                    } catch (e: Exception) {
                        // try the next mirror
                    }
                }
                if (!downloaded) {
                    post { onResult("دانلود نسخهٔ " + version + " ناموفق بود") }
                    return@Thread
                }
                if (expected.isNotEmpty() && sha256(apk) != expected) {
                    apk.delete()
                    post { onResult("بستهٔ دانلودشده سالم نیست") }
                    return@Thread
                }
                if (signer(ctx, null) == null || signer(ctx, null) != signer(ctx, apk)) {
                    apk.delete()
                    post { onResult("امضای بستهٔ جدید با این نسخه نمی‌خواند") }
                    return@Thread
                }
                if (!canInstall(ctx)) {
                    post { onResult("اجازهٔ نصب بسته را روشن کن و دوباره بزن") }
                    post { allowInstall(ctx) }
                    return@Thread
                }
                post { onResult("نسخهٔ " + version + " آمادهٔ نصب است") }
                post { install(ctx, apk) }
            } catch (e: Exception) {
                post { onResult("بررسی بروزرسانی ناموفق بود؛ از صفحهٔ انتشار دانلود کن") }
            }
        }.start()
    }

    private fun manifest(): JSONObject {
        var last: Exception? = null
        for (link in MANIFESTS) {
            try {
                return JSONObject(http(link))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no manifest")
    }

    /** Download links in order: the manifest mirrors first, then the official asset. */
    private fun mirrors(json: JSONObject): List<String> {
        val out = ArrayList<String>()
        val array = json.optJSONArray("mirrors")
        if (array != null) {
            for (i in 0 until array.length()) {
                val link = array.optString(i)
                if (link.isNotBlank()) out.add(link)
            }
        }
        val main = json.optString("url")
        if (main.isNotBlank()) out.add(main)
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
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MolidVPN/" + BuildConfig.VERSION_NAME)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun http(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MolidVPN/" + BuildConfig.VERSION_NAME)
        c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
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
