package com.safetap.sos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val version: String, val notes: String, val url: String, val sha256: String, val sizeBytes: Long)

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data object UpToDate : UpdateResult
    data object NotConfigured : UpdateResult
    data class Error(val message: String) : UpdateResult
}

/**
 * Guardian-approved updates from this project's GitHub Releases.
 *
 * Safety rules:
 *  - Only runs when the guardian opens the update screen (or opted in to a check when settings open).
 *  - Sends a plain HTTPS request to api.github.com; no device data, no identifiers.
 *  - Only downloads from github.com / githubusercontent.com over HTTPS.
 *  - The release MUST publish a SHA-256 for the APK; the download is rejected if it doesn't match.
 *  - The APK must have the same package name and signing certificate as the installed app.
 *  - Android's installer still asks the guardian to confirm. Nothing installs silently.
 */
object Updater {
    private const val MAX_APK_BYTES = 60L * 1024 * 1024

    fun configured() = BuildConfig.UPDATE_REPO.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
        !BuildConfig.UPDATE_REPO.startsWith("your-github-user")

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext UpdateResult.NotConfigured
        try {
            val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                when (conn.responseCode) {
                    200 -> {}
                    404 -> return@withContext UpdateResult.Error("No release has been published yet.")
                    403, 429 -> return@withContext UpdateResult.Error("The update server is busy. Try again later.")
                    else -> return@withContext UpdateResult.Error("The update server answered with error ${conn.responseCode}.")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext UpdateResult.UpToDate

                val assets = json.optJSONArray("assets") ?: return@withContext UpdateResult.Error("The release has no files.")
                var apk: JSONObject? = null
                var shaAsset: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.optString("name")
                    if (n.endsWith(".apk")) apk = a
                    if (n.endsWith(".apk.sha256")) shaAsset = a
                }
                val a = apk ?: return@withContext UpdateResult.Error("The release has no APK file.")
                var sha = a.optString("digest").removePrefix("sha256:").takeIf { a.optString("digest").startsWith("sha256:") }
                if (sha == null && shaAsset != null) {
                    sha = fetchText(shaAsset.getString("browser_download_url")).trim().split(Regex("\\s+")).firstOrNull()
                }
                if (sha == null || !sha.matches(Regex("[0-9a-fA-F]{64}"))) {
                    return@withContext UpdateResult.Error("The release doesn't include a SHA-256 checksum, so it was not trusted.")
                }
                val size = a.optLong("size", -1)
                if (size > MAX_APK_BYTES) return@withContext UpdateResult.Error("The update file is unexpectedly large.")
                UpdateResult.Available(UpdateInfo(tag.removePrefix("v"), cleanNotes(json.optString("body")), a.getString("browser_download_url"), sha.lowercase(), size))
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            UpdateResult.Error("Couldn't check for updates. Check the internet connection.")
        } catch (e: Exception) {
            UpdateResult.Error("Couldn't read the update information.")
        }
    }

    /** Downloads, checks SHA-256 and signing identity. Throws IllegalStateException with a readable message. */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "update.apk")
        try {
            val conn = open(info.url)
            try {
                check(conn.responseCode == 200) { "Download failed (error ${conn.responseCode})." }
                check(allowedHost(conn.url)) { "The download was redirected to an untrusted site and was blocked." }
                val total = conn.contentLengthLong
                check(total <= MAX_APK_BYTES) { "The update file is unexpectedly large." }
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                conn.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n); os.write(buf, 0, n)
                            done += n
                            check(done <= MAX_APK_BYTES) { "The update file is unexpectedly large." }
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                check(got == info.sha256) { "The downloaded file failed its SHA-256 check and was deleted." }
            } finally {
                conn.disconnect()
            }
            check(signedByUs(ctx, out)) { "The update is not signed by the same key as this app, so it was blocked." }
            out
        } catch (e: IOException) {
            out.delete()
            throw IllegalStateException("Download interrupted. Check the internet connection.")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** Hands the verified APK to Android's installer. The guardian must confirm there. */
    fun launchInstall(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }

    // ---- helpers ----

    /** Shows only plain release text: drops links and auto-generated hosting boilerplate. */
    internal fun cleanNotes(body: String): String = body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.contains("http", true) && !it.contains("github", true) &&
            !it.contains("changelog", true) && !it.startsWith("#") }
        .joinToString("\n").take(400)

    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun allowedHost(url: URL): Boolean {
        val h = url.host.lowercase()
        return url.protocol == "https" && (h == "github.com" || h.endsWith(".github.com") ||
            h == "githubusercontent.com" || h.endsWith(".githubusercontent.com"))
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        require(allowedHost(u)) { "Blocked an untrusted address." }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "PocketGuardian-Updater")
        }
    }

    private fun fetchText(url: String): String {
        val c = open(url)
        try {
            check(c.responseCode == 200) { "checksum download failed" }
            check(allowedHost(c.url)) { "untrusted redirect" }
            return c.inputStream.bufferedReader().use { it.readText() }.take(500)
        } finally { c.disconnect() }
    }

    private fun signedByUs(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
            if (archive.packageName != ctx.packageName) return false
            fun hashes(s: SigningInfo?) = s?.apkContentsSigners?.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }?.toSet() ?: emptySet()
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val theirs = hashes(archive.signingInfo)
            return theirs.isNotEmpty() && theirs == hashes(mine)
        }
        // Android 8.x: only the package name can be checked here; Android's installer itself
        // refuses an update signed with a different key.
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        return archive.packageName == ctx.packageName
    }
}
