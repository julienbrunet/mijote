package app.mijote.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A published app release: version name and the URLs of its APK and SHA-256 file. */
data class AppRelease(val version: String, val apkUrl: String, val sha256Url: String)

private const val RELEASES_URL = "https://api.github.com/repos/julienbrunet/mijote/releases?per_page=100"
private const val TAG_PREFIX = "android-v"

/** Parses `MAJOR.MINOR.PATCH` (suffixes such as `-dev` ignored) into a comparable triple. */
fun parseVersion(v: String): List<Int>? {
    val parts = v.substringBefore('-').substringBefore('+').split('.')
    if (parts.size != 3) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

fun isNewer(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return true
    for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

/**
 * Picks the highest-versioned published `android-v*` release from a GitHub releases list.
 * The list order is not trusted: a re-published old tag can come first.
 */
fun latestAppRelease(releasesJson: String): AppRelease? {
    val list = JSONArray(releasesJson)
    var best: AppRelease? = null
    for (i in 0 until list.length()) {
        val r = list.getJSONObject(i)
        val tag = r.optString("tag_name")
        if (r.optBoolean("draft") || r.optBoolean("prerelease") || !tag.startsWith(TAG_PREFIX)) continue
        val assets = r.optJSONArray("assets") ?: continue
        var apk: String? = null
        var sha: String? = null
        for (j in 0 until assets.length()) {
            val a = assets.getJSONObject(j)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk")) apk = url
            if (name.endsWith(".apk.sha256")) sha = url
        }
        val version = tag.removePrefix(TAG_PREFIX)
        if (apk != null && sha != null && parseVersion(version) != null && (best == null || isNewer(version, best.version))) {
            best = AppRelease(version, apk, sha)
        }
    }
    return best
}

/**
 * Self-update from the GitHub releases (the same ones the presentation site links to).
 * Blocking calls: run them on a background dispatcher. Android always asks the user to
 * confirm the installation of an app that does not come from a store.
 */
class Updater(private val context: Context) {

    val currentVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    /** Returns the latest release if it is newer than the installed version. */
    fun check(): AppRelease? {
        val release = latestAppRelease(get(RELEASES_URL).toString(Charsets.UTF_8)) ?: return null
        return release.takeIf { isNewer(it.version, currentVersion) }
    }

    /** Downloads the APK, checks its SHA-256 and hands it to the system installer. */
    fun downloadAndInstall(release: AppRelease) {
        val expected = get(release.sha256Url).toString(Charsets.UTF_8).trim().substringBefore(' ').lowercase()
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "mijote.apk")
        download(release.apkUrl, apk)
        val actual = sha256(apk)
        if (actual != expected) {
            apk.delete()
            throw IOException("Le fichier téléchargé est corrompu (empreinte SHA-256 différente).")
        }
        install(apk)
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("mijote.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, InstallResultReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c
    }

    private fun get(url: String): ByteArray {
        val c = open(url)
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    private fun download(url: String, to: File) {
        val c = open(url)
        try { c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } } } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Receives the installer status; shows the system confirmation screen when it is required. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
