package com.molidvpn.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.molidvpn.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Smart update: fetch a signed manifest, compare versionCode, verify the
 * SHA-256 of the APK *and* that it is signed by the same certificate as the
 * installed app before handing it to the installer.
 *
 * Point MANIFEST_URL at your release channel (GitHub Releases metadata or
 * your own endpoint). Empty means "not configured yet".
 */
object Updater {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/hidooch980/MolidVPN/main/release/update.json"

    fun check(ctx: Context, onResult: (String) -> Unit) {
        if (MANIFEST_URL.isBlank()) {
            onResult("نسخهٔ " + BuildConfig.VERSION_NAME + " تازه‌ترین نسخه است")
            return
        }
        Thread {
            try {
                val body = http(MANIFEST_URL)
                val json = JSONObject(body)
                val code = json.optInt("versionCode", 0)
                if (code <= BuildConfig.VERSION_CODE) {
                    post { onResult("نسخهٔ " + BuildConfig.VERSION_NAME + " تازه‌ترین نسخه است") }
                    return@Thread
                }
                val version = json.optString("versionName", code.toString())
                val expected = json.optString("sha256", "").lowercase()
                val apkUrl = json.getString("url")
                val apk = File(ctx.cacheDir, "molid-update.apk")
                if (apk.exists()) apk.delete()
                http(apkUrl, apk)

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
                post { onResult("نسخهٔ " + version + " آمادهٔ نصب است") }
                post { install(ctx, apk) }
            } catch (e: Exception) {
                post { onResult("بررسی بروزرسانی ناموفق بود") }
            }
        }.start()
    }

    private fun install(ctx: Context, apk: File) {
        val authority = ctx.packageName + ".updates"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun http(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
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
