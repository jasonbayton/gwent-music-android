package org.bayton.gmc.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.data.model.Envelope
import org.bayton.gmc.data.model.SecureMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.bayton.gmc.notify.NotificationAccountState

/** Exercises the repository's real diary and inbox recipient paths with no HTTP or stored account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageRecipientsTest {
    private val api = mock(ParitorApi::class.java)
    private lateinit var session: Session
    private lateinit var repo: GwentRepository
    private val diaryFailure = IllegalStateException("diary unavailable")
    private val inboxFailure = IllegalStateException("inbox unavailable")
    private val diary = Envelope(success = true, modelList = listOf(
        DiaryEntry(teacherID = "teacher-1", startDateTime = "2026-09-07T10:00:00", subject = "Brass")))
    private val inbox = Envelope(success = true, modelList = listOf(
        SecureMessage(fromContactID = "office-1", firstName = "Music", lastName = "Office")))

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        session = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        session.activate("parent-1", "parent@example.test")
        repo = GwentRepository(api, session)
    }

    @Test fun `both failed sources propagate a retryable error with both causes`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenThrow(diaryFailure)
        `when`(api.messagesReceived("parent-1", take = 50)).thenThrow(inboxFailure)
        val failure = runCatching { repo.messageRecipients() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertSame(diaryFailure, failure!!.cause)
        assertSame(inboxFailure, failure.suppressed.single())
    }

    @Test fun `teachers remain available when inbox fails`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(diary)
        `when`(api.messagesReceived("parent-1", take = 50)).thenThrow(inboxFailure)
        val recipients = repo.messageRecipients()
        assertEquals(listOf("teacher-1"), recipients.map { it.contactId })
        assertEquals("Instrumental teacher", recipients.single().subtitle)
    }

    @Test fun `inbox senders remain available when diary fails`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenThrow(diaryFailure)
        `when`(api.messagesReceived("parent-1", take = 50)).thenReturn(inbox)
        val recipients = repo.messageRecipients()
        assertEquals(listOf("office-1"), recipients.map { it.contactId })
        assertEquals("Music Office", recipients.single().name)
    }

    @Test fun `genuine empty responses remain a successful empty directory`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(Envelope(success = true))
        `when`(api.messagesReceived("parent-1", take = 50)).thenReturn(Envelope(success = true))
        assertTrue(repo.messageRecipients().isEmpty())
    }

    @Test fun `successful empty teacher source is retained when inbox fails`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(Envelope(success = true))
        `when`(api.messagesReceived("parent-1", take = 50)).thenThrow(inboxFailure)
        assertTrue(repo.messageRecipients().isEmpty())
    }

    @Test fun `successful empty inbox source is retained when diary fails`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenThrow(diaryFailure)
        `when`(api.messagesReceived("parent-1", take = 50)).thenReturn(Envelope(success = true))
        assertTrue(repo.messageRecipients().isEmpty())
    }

    @Test fun `successful sources merge with teachers first and remove duplicate contacts`() = runBlocking {
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(diary)
        `when`(api.messagesReceived("parent-1", take = 50)).thenReturn(inbox.copy(
            modelList = inbox.modelList + SecureMessage(fromContactID = "teacher-1", firstName = "Other name")))
        val recipients = repo.messageRecipients()
        assertEquals(listOf("teacher-1", "office-1"), recipients.map { it.contactId })
        assertEquals("Instrumental teacher", recipients.first().subtitle)
    }

    @Test fun `teacher cancellation propagates without starting inbox lookup`() = runBlocking {
        val cancelled = CancellationException("cancelled diary request")
        `when`(api.diary(anyString(), anyString(), anyString())).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.messageRecipients() }.exceptionOrNull())
        verify(api, never()).messagesReceived(anyString(), anyInt(), anyInt(), anyBoolean())
        Unit
    }

    @Test fun `inbox cancellation propagates despite successful teachers`() = runBlocking {
        val cancelled = CancellationException("cancelled inbox request")
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(diary)
        `when`(api.messagesReceived("parent-1", take = 50)).thenThrow(cancelled)
        assertSame(cancelled, runCatching { repo.messageRecipients() }.exceptionOrNull())
    }
}
