package org.bayton.gmc.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The only release endpoint used by this optional updater. */
internal const val UPDATE_MANIFEST_URL = "https://cdn.bayton.org/download/gmc/latest.json"

@Serializable
internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String = "",
) {
    fun isNewerThan(installed: Long): Boolean = versionCode > installed

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UpdateManifest = json.decodeFromString<UpdateManifest>(text).also {
            require(it.versionCode in 1..2_100_000_000L) { "Invalid release version code." }
            require(it.versionName.isNotBlank() && it.versionName.length <= 100) { "Invalid release version name." }
            require(it.releaseNotes.length <= 20_000) { "Release notes are too long." }
            require(it.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "The release checksum must be SHA-256." }
            requireAllowedUrl(it.apkUrl)
        }
    }
}

/** Do not follow a manifest or redirect onto an unrelated host or insecure transport. */
internal fun requireAllowedUrl(value: String) {
    val url = requireNotNull(value.toHttpUrlOrNull()) { "Invalid update URL." }
    require(url.isHttps && url.host == "cdn.bayton.org" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        url.encodedPath.startsWith("/download/gmc/")) { "Update URL is outside the approved CDN location." }
}

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String?, val signers: Set<String>)

/** Require the same current signer set. Signing-key rotation is deliberately not supported. */
internal fun validateIdentity(installed: ApkIdentity, candidate: ApkIdentity, release: UpdateManifest) {
    require(candidate.packageName == installed.packageName) { "The APK belongs to a different app." }
    require(candidate.versionCode == release.versionCode && candidate.versionName == release.versionName) {
        "The APK version does not match the release manifest."
    }
    require(release.isNewerThan(installed.versionCode)) { "This update is not newer than the installed app." }
    require(installed.signers.isNotEmpty() && candidate.signers == installed.signers) {
        "The APK signing certificate does not match this installation."
    }
}
