package org.bayton.gmc.update

import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val checksum = "a".repeat(64)
    private fun manifest(code: Long = 3, hash: String = checksum, url: String = "https://cdn.bayton.org/download/gmc/gmc-1.3.apk") =
        """{"versionCode":$code,"versionName":"1.3","apkUrl":"$url","sha256":"$hash"}"""

    @Test fun `numeric version codes govern update availability`() {
        val release = UpdateManifest.parse(manifest())
        assertTrue(release.isNewerThan(2))
        assertFalse(release.isNewerThan(3))
        assertFalse(release.isNewerThan(10))
    }

    @Test fun `invalid versions hashes and missing fields are rejected`() {
        for (text in listOf(manifest(0), manifest(2_100_000_001), manifest(hash = "a".repeat(32)), "{}", "not json")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(text) }
        }
    }

    @Test fun `only approved HTTPS CDN URLs are accepted`() {
        for (url in listOf("http://cdn.bayton.org/download/gmc/a.apk", "https://other.test/download/gmc/a.apk",
            "https://cdn.bayton.org:8443/download/gmc/a.apk", "https://user@cdn.bayton.org/download/gmc/a.apk",
            "https://cdn.bayton.org/download/other/a.apk", "https://cdn.bayton.org/download/gmc/../other/a.apk")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(manifest(url = url)) }
        }
    }

    @Test fun `identity requires matching app version and signer`() {
        val installed = ApkIdentity("org.bayton.gmc", 2, "1.2", setOf("signer"))
        val candidate = installed.copy(versionCode = 3, versionName = "1.3")
        val release = UpdateManifest.parse(manifest())
        validateIdentity(installed, candidate, release)
        for (bad in listOf(candidate.copy(packageName = "other"), candidate.copy(versionCode = 4),
            candidate.copy(versionName = "1.2"), candidate.copy(signers = setOf("other")), candidate.copy(signers = emptySet()))) {
            assertThrows(IllegalArgumentException::class.java) { validateIdentity(installed, bad, release) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateIdentity(candidate, candidate, release) }
    }

    @Test fun `daily throttle handles first open repeat opens and clock rollback`() {
        assertTrue(checkIsDue(100, 0))
        assertFalse(checkIsDue(101, 100))
        assertFalse(checkIsDue(86_400_099, 100))
        assertTrue(checkIsDue(86_400_100, 100))
        assertTrue(checkIsDue(50, 100))
    }
}
