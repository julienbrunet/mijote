package app.mijote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersions() {
        assertTrue(isNewer("0.1.15", "0.1.14"))
        assertTrue(isNewer("0.2.0", "0.1.99"))
        assertTrue(isNewer("0.1.100", "0.1.99"))
        assertFalse(isNewer("0.1.14", "0.1.14"))
        assertFalse(isNewer("0.1.13", "0.1.14"))
        assertFalse(isNewer("garbage", "0.1.14"))
        assertTrue(isNewer("0.1.1", "0.0.0-dev"))
    }

    @Test
    fun picksLatestAndroidRelease() {
        val json = """[
          {"tag_name":"server-v0.1.9","draft":false,"prerelease":false,"assets":[]},
          {"tag_name":"android-v0.1.16","draft":true,"prerelease":false,"assets":[]},
          {"tag_name":"android-v0.1.15","draft":false,"prerelease":false,"assets":[
            {"name":"mijote-0.1.15.apk","browser_download_url":"https://x/a.apk"},
            {"name":"mijote-0.1.15.apk.sha256","browser_download_url":"https://x/a.apk.sha256"}]},
          {"tag_name":"android-v0.1.14","draft":false,"prerelease":false,"assets":[]}
        ]"""
        assertEquals(AppRelease("0.1.15", "https://x/a.apk", "https://x/a.apk.sha256"), latestAppRelease(json))
        assertNull(latestAppRelease("[]"))
    }
}
