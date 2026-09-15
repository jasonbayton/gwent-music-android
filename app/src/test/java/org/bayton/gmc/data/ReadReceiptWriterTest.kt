package org.bayton.gmc.data

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bayton.gmc.data.api.ParitorApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadReceiptWriterTest {
    private val api = mock(ParitorApi::class.java)
    private val writer = ReadReceiptWriter(api)
    // Mockito records the matcher but returns null; Kotlin's API requires a non-null argument.
    private fun anyModel(): JsonObject = any(JsonObject::class.java) ?: JsonObject(emptyMap())
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private val unread = json("""{"success":true,"model":{"objectState":0,"annotation":"private-content","viewedDate":null}}""")
    private val read = json("""{"success":true,"model":{"viewedDate":"2026-09-06T01:00:00"}}""")
    private val ok = json("""{"success":true}""")
    private fun logs() = ShadowLog.getLogsForTag("GmcReadReceipt").joinToString("\n") { it.msg }

    @Test fun `accepted write must be confirmed and preserves unrelated fields`() = runBlocking {
        `when`(api.contactActivityItemRaw("private-id")).thenReturn(unread, read)
        `when`(api.updateContactActivityItem(anyModel())).thenAnswer {
            val body = it.arguments[0] as JsonObject
            assertEquals(JsonPrimitive(1), body["objectState"])
            assertEquals(JsonPrimitive("private-content"), body["annotation"])
            assertTrue(body["viewedDate"]!!.jsonPrimitive.content.isNotBlank())
            ok
        }
        assertTrue(writer.markRead("private-id"))
        verify(api, times(2)).contactActivityItemRaw("private-id")
        assertTrue(logs().contains("confirmed"))
        assertFalse(logs().contains("private-"))
    }

    @Test fun `application rejection logs numeric reason but no server text`() = runBlocking<Unit> {
        `when`(api.contactActivityItemRaw("private-id")).thenReturn(unread)
        `when`(api.updateContactActivityItem(anyModel())).thenReturn(json(
            """{"success":false,"errorContext":{"errorCode":42,"message":"private-server-text"},"resultInformation":"private-info"}"""))
        assertFalse(writer.markRead("private-id"))
        assertTrue(logs().contains("stage=update"))
        assertTrue(logs().contains("errorCode=42"))
        assertFalse(logs().contains("private-"))
        verify(api, times(1)).contactActivityItemRaw("private-id")
    }

    @Test fun `acknowledged but unchanged server date is a failure`() = runBlocking {
        `when`(api.contactActivityItemRaw("id")).thenReturn(unread)
        `when`(api.updateContactActivityItem(anyModel())).thenReturn(ok)
        assertFalse(writer.markRead("id"))
        assertTrue(logs().contains("stage=verify reason=viewed_date_not_persisted originalObjectState=0"))
    }

    @Test fun `missing model and failed fetch never write`() = runBlocking<Unit> {
        for (reply in listOf(ok, json("""{"success":false}"""), json("""{"model":{}}"""),
            json("""{"success":true,"abort":true}"""), json("""{"success":true,"internetConnection":false}"""))) {
            `when`(api.contactActivityItemRaw("id")).thenReturn(reply)
            assertFalse(writer.markRead("id"))
        }
        verify(api, never()).updateContactActivityItem(anyModel())
    }

    @Test fun `already viewed server item does not write`() = runBlocking<Unit> {
        `when`(api.contactActivityItemRaw("id")).thenReturn(read)
        assertTrue(writer.markRead("id"))
        verify(api, never()).updateContactActivityItem(anyModel())
    }

    @Test fun `HTTP failure reports status without response content`() = runBlocking {
        val response = retrofit2.Response.error<JsonObject>(403,
            "private-response".toResponseBody())
        `when`(api.contactActivityItemRaw("private-id")).thenThrow(retrofit2.HttpException(response))
        assertFalse(writer.markRead("private-id"))
        assertTrue(logs().contains("stage=fetch reason=http_403"))
        assertFalse(logs().contains("private-"))
    }

    @Test fun `verification rejection is not mistaken for a confirmed write`() = runBlocking {
        `when`(api.contactActivityItemRaw("id")).thenReturn(unread, json("""{"success":false}"""))
        `when`(api.updateContactActivityItem(anyModel())).thenReturn(ok)
        assertFalse(writer.markRead("id"))
        assertTrue(logs().contains("stage=verify reason=rejected"))
    }

    @Test fun `exceptions omit messages and cancellation propagates`() = runBlocking {
        `when`(api.contactActivityItemRaw("id")).thenThrow(IllegalStateException("private-token"))
        assertFalse(writer.markRead("id"))
        assertTrue(logs().contains("unexpected_error"))
        assertFalse(logs().contains("private-token"))
        reset(api)
        `when`(api.contactActivityItemRaw("id")).thenThrow(CancellationException("private-token"))
        val failure = runCatching { writer.markRead("id") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(logs().contains("cancelled stage=fetch"))
        assertFalse(logs().contains("private-token"))
    }
}
