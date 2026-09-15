package org.bayton.gmc.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val MAX_APK_BYTES = 200L * 1024 * 1024

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Dedicated client: no parent-account headers, cookies, interceptors or persistent HTTP cache. */
internal class UpdateRepository(
    private val client: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES).build(),
) {
    suspend fun check(): UpdateManifest = request(UPDATE_MANIFEST_URL) { response, active ->
        val bytes = response.body.byteStream().use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                check(active()) { "Update check cancelled." }
                val count = input.read(chunk)
                if (count < 0) break
                require(buffer.size() + count <= 64 * 1024) { "Release manifest is too large." }
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        UpdateManifest.parse(bytes.toString(Charsets.UTF_8))
    }

    suspend fun download(
        release: UpdateManifest,
        directory: File,
        validate: (File) -> Unit,
        progress: (Long, Long?) -> Unit,
    ): File {
        requireAllowedUrl(release.apkUrl)
        return request(release.apkUrl) { response, active ->
            check(directory.isDirectory || directory.mkdirs()) { "Could not create the update cache." }
            val part = File.createTempFile("update-", ".part", directory)
            val apk = File(directory, part.nameWithoutExtension + ".apk")
            var complete = false
            try {
                val length = response.body.contentLength().takeIf { it >= 0 }
                require(length == null || length <= MAX_APK_BYTES) { "The APK is too large." }
                var received = 0L
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            check(active()) { "Download cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= MAX_APK_BYTES) { "The APK is too large." }
                            output.write(buffer, 0, count)
                            progress(received, length)
                        }
                    }
                }
                require(received > 0 && (length == null || received == length)) { "The APK download is incomplete." }
                require(sha256(part).equals(release.sha256, ignoreCase = true)) { "The APK checksum does not match. Please try again later." }
                validate(part)
                check(active()) { "Download cancelled." }
                check(part.renameTo(apk)) { "Could not prepare the APK." }
                complete = true
                apk
            } finally {
                part.delete()
                if (!complete) apk.delete()
            }
        }
    }

    private suspend fun <T> request(url: String, consume: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            requireAllowedUrl(url)
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            require(it.code == 200) { "The update server returned HTTP ${it.code}." }
                            consume(it) { continuation.isActive }
                        }
                        continuation.resume(result) { _, value, _ ->
                            // Cancellation can race with a completed file being delivered to the UI.
                            if (value is File) value.delete()
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
}

@Suppress("DEPRECATION") // Flag overload works across the app's API 28+ range.
internal fun installedIdentity(context: Context): ApkIdentity =
    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).identity()

@Suppress("DEPRECATION")
internal fun validateApk(context: Context, file: File, release: UpdateManifest) {
    val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)) {
        "The downloaded file is not a readable APK."
    }
    validateIdentity(installedIdentity(context), info.identity(), release)
}

private fun PackageInfo.identity() = ApkIdentity(
    packageName, longVersionCode, versionName,
    signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty(),
)
