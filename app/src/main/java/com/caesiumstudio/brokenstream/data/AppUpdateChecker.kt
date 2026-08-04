package com.caesiumstudio.bitstream.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/caesiumstudio/bitstream/main/update.json"
    private const val PREFS_NAME = "bitstream_update"
    private const val KEY_LAST_CHECKED = "last_checked_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val AUTHORITY = "com.caesiumstudio.bitstream.fileprovider"

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastChecked = prefs.getLong(KEY_LAST_CHECKED, 0L)
        return (System.currentTimeMillis() - lastChecked) >= CHECK_INTERVAL_MS
    }

    fun recordChecked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply()
    }

    fun fetchUpdateInfo(url: String): UpdateInfo? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/json")
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                changelog = json.optString("changelog", "")
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    fun isUpdateAvailable(context: Context, remoteVersionCode: Int): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val localVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            }
            remoteVersionCode > localVersionCode
        } catch (_: Exception) {
            false
        }
    }

    fun downloadApk(context: Context, apkUrl: String, onProgress: (Int) -> Unit): File? {
        val apkFile = File(context.filesDir, "update/bitstream-update.apk")
        apkFile.parentFile?.mkdirs()
        apkFile.delete()

        val conn = URL(apkUrl).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val contentLength = conn.contentLength
            var bytesRead = 0L
            val buffer = ByteArray(8192)

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(apkFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            onProgress((bytesRead * 100 / contentLength).toInt())
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
            apkFile
        } catch (_: Exception) {
            apkFile.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, apkFile)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        context.startActivity(intent)
    }

    fun canInstallUnknownSources(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
