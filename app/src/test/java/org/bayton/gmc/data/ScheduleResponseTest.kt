package org.bayton.gmc.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.data.model.Envelope
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException

class ScheduleResponseTest {
    @Test fun `term wire fields decode and repository rejects invalid envelopes and identities`() = runBlocking {
        val dates = Json.decodeFromString<org.bayton.gmc.data.model.AcademicTermDates>(
            """{"academicYearTerm":"term","academicYear":"year","fromDate":"2026-01-01T00:00:00","toDate":"2026-04-30T00:00:00"}""")
        val key = TermKey("year", "term")
        `when`(api.academicTerm("term")).thenReturn(org.bayton.gmc.data.model.EnvelopeOne(dates, success = true))
        assertEquals(java.time.LocalDate.of(2026, 1, 1), repo.historyTermDates(key).start)
        for (reply in listOf(
            org.bayton.gmc.data.model.EnvelopeOne(dates, success = false),
            org.bayton.gmc.data.model.EnvelopeOne(dates, success = true, abort = true),
            org.bayton.gmc.data.model.EnvelopeOne(dates, success = true, internetConnection = false),
            org.bayton.gmc.data.model.EnvelopeOne(dates.copy(academicYear = "wrong"), success = true),
            org.bayton.gmc.data.model.EnvelopeOne(dates.copy(academicYearTerm = "wrong"), success = true),
            org.bayton.gmc.data.model.EnvelopeOne(dates.copy(fromDate = "invalid"), success = true),
        )) {
            `when`(api.academicTerm("term")).thenReturn(reply)
            assertThrows(IOException::class.java) { runBlocking { repo.historyTermDates(key) } }
        }
    }
    private val api = mock(ParitorApi::class.java)
    private val session = mock(Session::class.java).also { `when`(it.contactId).thenReturn("fixture") }
    private val repo = GwentRepository(api, session)
    private val lesson = DiaryEntry(diaryID = "lesson", startDateTime = "2026-09-10T08:00:00")

    private suspend fun response(envelope: Envelope<DiaryEntry>) {
        `when`(api.diary(anyString(), anyString(), anyString())).thenReturn(envelope)
    }

    @Test fun `failed disconnected aborted or paginated diaries are not empty successes`() {
        for (envelope in listOf(
            Envelope<DiaryEntry>(success = false),
            Envelope<DiaryEntry>(success = true, abort = true),
            Envelope<DiaryEntry>(success = true, internetConnection = false),
            Envelope(listOf(lesson), success = true, nextLink = "page-2"),
        )) {
            runBlocking { response(envelope) }
            assertThrows(IOException::class.java) { runBlocking { repo.schedule() } }
        }
    }

    @Test fun `malformed lesson prevents silently accepting a partial schedule`() {
        runBlocking {
            response(Envelope(listOf(lesson, DiaryEntry(diaryID = "bad", startDateTime = "invalid")), success = true))
        }
        assertThrows(IOException::class.java) { runBlocking { repo.schedule() } }
    }

    @Test fun `valid full and genuinely empty schedules remain supported`() = runBlocking {
        response(Envelope(listOf(lesson), success = true))
        assertEquals(listOf(lesson), repo.schedule())
        response(Envelope(emptyList(), success = true))
        assertTrue(repo.schedule().isEmpty())
    }

    @Test fun `missing list is malformed rather than silently empty`() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<Envelope<DiaryEntry>>("{\"success\":true}")
        }
    }

    @Test fun `write acknowledgement remains compatible with success-only response`() {
        assertTrue(Json.decodeFromString<org.bayton.gmc.data.model.OperationResult>("{\"success\":true}").success)
    }
}
