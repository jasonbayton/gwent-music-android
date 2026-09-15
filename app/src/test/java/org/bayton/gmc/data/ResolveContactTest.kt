package org.bayton.gmc.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.Contact
import org.bayton.gmc.data.model.Envelope
import org.bayton.gmc.data.model.ServiceResponseException
import org.bayton.gmc.notify.NotificationAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Sign-in contact resolution contract: resolve by email first, then the Schooble-ID (oid) lookup.
 * Return null (genuinely no account) ONLY when every attempted lookup completed and came back empty.
 * If ANY unresolved path errored and nothing matched - including a mixed empty-plus-error result -
 * PROPAGATE the error so the UI offers a retry: an error may be masking a real match, and a false
 * "no account" for an account that exists is the worse failure. Cancellation must pass through
 * unchanged, and a failed resolve must leave the session cleared (no stale identity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ResolveContactTest {
    private lateinit var api: ParitorApi
    private lateinit var session: Session
    private lateinit var repo: GwentRepository

    @Before fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        // Not activated: no cached ContactID, so resolveContact runs the lookups.
        session = Session(app.getSharedPreferences("s", Context.MODE_PRIVATE), NotificationAccountState(app))
        api = mock(ParitorApi::class.java)
        repo = GwentRepository(api, session)
    }

    private fun found(id: String) = Envelope(listOf(Contact(contactID = id)), success = true)
    private fun empty() = Envelope(emptyList<Contact>(), success = true)
    private fun failed() = Envelope(emptyList<Contact>(), success = false)   // success=false -> validatedList() throws

    @Test fun `email resolves without touching the Schooble-ID lookup`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(found("C-EMAIL"))
        assertEquals("C-EMAIL", repo.resolveContact("parent@example.test", "OID-1"))
        verify(api, never()).contactBySchoobleContactId("OID-1")
        assertEquals("C-EMAIL", session.contactId)
        Unit
    }

    @Test fun `a failed email lookup falls back to the Schooble-ID lookup`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(failed())
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(found("C-OID"))
        assertEquals("C-OID", repo.resolveContact("parent@example.test", "OID-1"))
        assertEquals("C-OID", session.contactId)
    }

    @Test fun `both lookups failing propagates for a retry`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(failed())
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(failed())
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.resolveContact("parent@example.test", "OID-1") } }
        assertNull(session.contactId)   // failed resolve leaves no stale identity
    }

    @Test fun `a genuinely empty result on every path is no match, not an error`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(empty())
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(empty())
        assertNull(repo.resolveContact("parent@example.test", "OID-1"))
        verify(api).contactBySchoobleContactId("OID-1")   // an empty email still tries the oid path
        assertNull(session.contactId)
    }

    // Mixed empty/error: nothing matched but a path errored -> retry, NOT a false "no account".
    @Test fun `email empty then oid error propagates for a retry`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(empty())
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(failed())
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.resolveContact("parent@example.test", "OID-1") } }
        assertNull(session.contactId)
    }

    @Test fun `email error then oid empty propagates for a retry`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(failed())
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(empty())
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.resolveContact("parent@example.test", "OID-1") } }
        assertNull(session.contactId)
    }

    // Sole-identifier paths: only one of email/oid is present.
    @Test fun `only an oid resolves without touching the email lookup`() = runBlocking {
        `when`(api.contactBySchoobleContactId("OID-1")).thenReturn(found("C-OID"))
        assertEquals("C-OID", repo.resolveContact(email = null, oid = "OID-1"))
        verify(api, never()).contactByEmail(anyString())
        assertEquals("C-OID", session.contactId)
    }

    @Test fun `only an email that is empty is a clean no match`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(empty())
        assertNull(repo.resolveContact(email = "parent@example.test", oid = null))
        verify(api, never()).contactBySchoobleContactId(anyString())
        assertNull(session.contactId)
    }

    // Cancellation must pass through unchanged - never be reported as a service failure or no-match.
    @Test fun `cancellation propagates as cancellation`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenThrow(CancellationException())
        assertThrows(CancellationException::class.java) { runBlocking { repo.resolveContact("parent@example.test", "OID-1") } }
        verify(api, never()).contactBySchoobleContactId(anyString())   // cancellation does not fall through to oid
        Unit
    }
}
