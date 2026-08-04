package com.mooncity.gpsmock.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams a release APK straight into a PackageInstaller session.
 *
 * Android always shows its own confirmation before replacing an app, and it rejects any
 * package whose signature does not match the installed one — so a tampered update URL
 * cannot swap in different code, it can only fail to install.
 */
object Updater {

    sealed interface Result {
        data object Committed : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GpsMock")
            }
        } catch (e: Exception) {
            return@withContext Result.Failed(e.message ?: "connect failed")
        }

        try {
            if (conn.responseCode !in 200..299) {
                return@withContext Result.Failed("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L

            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(ctx.packageName)
                if (total > 0) setSize(total)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("gpsmock.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(((done * 100) / total).toInt())
                        }
                    }
                    session.fsync(out)
                }

                val callback = PendingIntent.getBroadcast(
                    ctx,
                    sessionId,
                    Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION),
                    // Mutable: the system fills in the status extras before delivering.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(callback.intentSender)
            }
            Result.Committed
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
