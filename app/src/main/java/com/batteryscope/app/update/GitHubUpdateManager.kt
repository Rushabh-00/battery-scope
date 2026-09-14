package com.batteryscope.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GitHubUpdateManager {
    data class Release(
        override val versionName: String,
        override val downloadUrl: String,
    ) : com.batteryscope.app.ui.GithubUpdateManager.Release

    suspend fun checkLatest(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BatteryScope-App")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("tag_name").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val asset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_ASSET_NAME }
                ?: return@withContext null
            val downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                ?: return@withContext null
            if (compareVersions(version, currentVersion) > 0) {
                Release(version, downloadUrl)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(context: Context, release: com.batteryscope.app.ui.GithubUpdateManager.Release) = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, APK_FILE_NAME)
        download(release.downloadUrl, file)
        withContext(Dispatchers.Main) {
            launchInstallerWhenPermitted(context, file)
        }
    }

    private fun download(downloadUrl: String, destination: File) {
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BatteryScope-App")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub download failed: HTTP ${connection.responseCode}")
            }
            val temporary = File(destination.parentFile, "$APK_FILE_NAME.part")
            temporary.delete()
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                error("Could not prepare downloaded APK")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun launchInstallerWhenPermitted(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)

            // The user can grant the permission in Settings without returning through
            // the app's update button. Keep the downloaded APK and continue automatically
            // as soon as the permission becomes available.
            var permitted = false
            repeat(INSTALL_PERMISSION_WAIT_ATTEMPTS) {
                delay(INSTALL_PERMISSION_POLL_MS)
                if (context.packageManager.canRequestPackageInstalls()) {
                    permitted = true
                    return@repeat
                }
            }
            if (!permitted) {
                error("Install permission was not granted")
            }
        }

        if (!apk.isFile || apk.length() <= 0L) {
            error("Downloaded APK is no longer available")
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            type = "application/vnd.android.package-archive"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val b = right.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val count = maxOf(a.size, b.size)
        for (index in 0 until count) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private const val RELEASES_API = "https://api.github.com/repos/Rushabh-00/battery-scope/releases/latest"
    private const val APK_ASSET_NAME = "BatteryScope-release.apk"
    private const val APK_FILE_NAME = "BatteryScope-update.apk"
    private const val INSTALL_PERMISSION_POLL_MS = 500L
    private const val INSTALL_PERMISSION_WAIT_ATTEMPTS = 600
}
