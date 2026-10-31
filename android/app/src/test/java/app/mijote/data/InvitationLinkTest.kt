package app.mijote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationLinkTest {
    @Test
    fun parsesTheServerLink() {
        val l = parseInvitationLink("mijote://invite?server=https%3A%2F%2Fmesrepas.example.fr&code=mfrggzdfmztwq2lk")
        assertEquals(InvitationLink("https://mesrepas.example.fr", "mfrggzdfmztwq2lk"), l)
    }

    @Test
    fun refusesOtherLinksAndCleartextServers() {
        assertNull(parseInvitationLink(null))
        assertNull(parseInvitationLink("https://example.fr/invite?server=https%3A%2F%2Fa.fr&code=mfrggzdfmztwq2lk"))
        assertNull(parseInvitationLink("mijote://other?server=https%3A%2F%2Fa.fr&code=mfrggzdfmztwq2lk"))
        assertNull(parseInvitationLink("mijote://invite?server=http%3A%2F%2Fa.fr&code=mfrggzdfmztwq2lk"))
        assertNull(parseInvitationLink("mijote://invite?server=https%3A%2F%2Fa.fr&code=x"))
        assertNull(parseInvitationLink("mijote://invite?code=mfrggzdfmztwq2lk"))
    }

    @Test
    fun cleansTypedCodes() {
        assertEquals("mfrggzdfmztwq2lk", normalizeInvitationCode(" MFRGG ZDFM-ZTWQ2LK\n"))
        assertNull(normalizeInvitationCode("short"))
    }
}
