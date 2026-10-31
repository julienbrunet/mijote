package app.mijote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizes() {
        assertEquals("https://mesrepas.example.fr", normalizeServerUrl("  mesrepas.example.fr/ "))
        assertEquals("https://example.fr:8443/mijote", normalizeServerUrl("https://example.fr:8443/mijote/"))
    }

    @Test
    fun refusesCleartextAndGarbage() {
        assertNull(normalizeServerUrl("http://192.168.1.15:8082"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("https://"))
    }
}
