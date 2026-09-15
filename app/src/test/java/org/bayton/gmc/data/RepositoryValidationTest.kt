package org.bayton.gmc.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.Contact
import org.bayton.gmc.data.model.Envelope
import org.bayton.gmc.data.model.RelatedPupil
import org.bayton.gmc.data.model.SecureMessage
import org.bayton.gmc.data.model.ServiceResponseException
import org.bayton.gmc.notify.NotificationAccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.bayton.gmc.data.model.DiaryEntry
import java.time.LocalDate
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An HTTP-200 response with success=false must not look like empty data: it must fail
 *  (ServiceResponseException) so the cache-first screens keep their snapshot instead of wiping it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RepositoryValidationTest {
    private lateinit var api: ParitorApi
    private lateinit var repo: GwentRepository

    @Before fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        val session = Session(app.getSharedPreferences("s", Context.MODE_PRIVATE), NotificationAccountState(app))
        session.activate("cid-1", "parent@example.test")
        api = mock(ParitorApi::class.java)
        repo = GwentRepository(api, session)
    }

    @Test fun `home messages rejects an unsuccessful envelope`() = runBlocking {
        `when`(api.messagesReceived("cid-1", 25, 0, true)).thenReturn(Envelope(emptyList(), success = false))
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.homeMessages() } }
        Unit
    }

    @Test fun `home messages accepts a successful empty inbox`() = runBlocking {
        // Negative control: a genuine empty inbox (success=true) is not an error.
        `when`(api.messagesReceived("cid-1", 25, 0, true)).thenReturn(Envelope(emptyList(), success = true))
        assertEquals(emptyList<Any>(), repo.homeMessages())
    }

    @Test fun `pupils rejects an unsuccessful envelope`() = runBlocking {
        `when`(api.relatedPupils("cid-1")).thenReturn(Envelope(emptyList<RelatedPupil>(), success = false))
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.pupils() } }
        Unit
    }

    @Test fun `account rejects an unsuccessful envelope`() = runBlocking {
        `when`(api.contactByEmail("parent@example.test")).thenReturn(Envelope(emptyList<Contact>(), success = false))
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.account() } }
        Unit
    }

    @Test fun `bounded diary transport pads inclusive end and sorts results`() = runBlocking<Unit> {
        val start = LocalDate.of(2026, 9, 1)
        val end = start.plusDays(2)
        val first = DiaryEntry(diaryID = "first", startDateTime = "2026-09-01T10:00:00")
        val last = DiaryEntry(diaryID = "last", startDateTime = "2026-09-03T10:00:00")
        `when`(api.diary("cid-1", start.toString(), end.plusDays(1).toString()))
            .thenReturn(Envelope(listOf(last, first), success = true))
        assertEquals(listOf(first, last), repo.diaryBetween(start, end))
        verify(api).diary("cid-1", "2026-09-01", "2026-09-04")
    }

    @Test fun `inverted diary range is rejected before transport`() = runBlocking<Unit> {
        val start = LocalDate.of(2026, 9, 3)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.diaryBetween(start, start.minusDays(1)) } }
        verifyNoInteractions(api)
    }

    @Test fun `malformed diary date fails rather than silently dropping a lesson`() = runBlocking<Unit> {
        val day = LocalDate.of(2026, 9, 1)
        `when`(api.diary("cid-1", day.toString(), day.plusDays(1).toString()))
            .thenReturn(Envelope(listOf(DiaryEntry(startDateTime = "invalid")), success = true))
        assertThrows(ServiceResponseException::class.java) { runBlocking { repo.diaryBetween(day, day) } }
    }

    @Test fun `term probe with no lessons does not invent progress`() = runBlocking<Unit> {
        val today = LocalDate.now()
        `when`(api.diary("cid-1", today.minusDays(31).toString(), today.plusDays(45).toString()))
            .thenReturn(Envelope(emptyList<DiaryEntry>(), success = true))
        assertEquals(org.bayton.gmc.ui.home.TermProgress(0, 0), repo.termProgress())
    }

    @Test fun `overview requests forward window and accepts genuinely empty lessons`() = runBlocking<Unit> {
        val today = LocalDate.now()
        `when`(api.diary("cid-1", today.toString(), today.plusMonths(4).toString()))
            .thenReturn(Envelope(emptyList<DiaryEntry>(), success = true))
        `when`(api.relatedPupils("cid-1")).thenReturn(Envelope(emptyList<RelatedPupil>(), success = true))
        val overview = repo.homeOverview()
        org.junit.Assert.assertNull(overview.next)
        org.junit.Assert.assertTrue(overview.nextUp.isEmpty())
        verify(api).diary("cid-1", today.toString(), today.plusMonths(4).toString())
    }
}
