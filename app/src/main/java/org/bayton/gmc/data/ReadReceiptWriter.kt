package org.bayton.gmc.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.bayton.gmc.data.api.ParitorApi
import retrofit2.HttpException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Diagnose acknowledgements separately from persistence. Never log payloads or identifiers. */
internal class ReadReceiptWriter(private val api: ParitorApi) {
    suspend fun markRead(itemId: String): Boolean {
        var stage = "fetch"
        try {
            val fetched = api.contactActivityItemRaw(itemId)
            if (!accepted(fetched, stage)) return false
            val model = fetched["model"] as? JsonObject ?: return failure(stage, "missing_model")
            if (viewed(model)) return true
            stage = "update"
            // The Xperios client defines Unchanged=0, Modified=1, Added=2, Deleted=4.
            // Posting an unchanged item can acknowledge success without saving viewedDate.
            val state = (model["objectState"] as? JsonPrimitive)?.intOrNull
            val updated = JsonObject(model.toMutableMap().apply {
                put("objectState", JsonPrimitive(1))
                put("viewedDate", JsonPrimitive(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
            })
            if (!accepted(api.updateContactActivityItem(updated), stage)) return false
            stage = "verify"
            val verified = api.contactActivityItemRaw(itemId)
            if (!accepted(verified, stage)) return false
            val saved = verified["model"] as? JsonObject ?: return failure(stage, "missing_model")
            if (!viewed(saved)) return failure(stage, "viewed_date_not_persisted originalObjectState=$state")
            Log.i(TAG, "Read receipt confirmed by fresh fetch")
            return true
        } catch (cancelled: CancellationException) {
            // Leaving the detail screen can cancel its work. Do not turn cancellation into success.
            Log.i(TAG, "Read receipt cancelled stage=$stage")
            throw cancelled
        } catch (error: Exception) {
            // Exception messages, server error text and stack traces can contain private data.
            val reason = when (error) {
                is HttpException -> "http_${error.code()}"
                is java.io.IOException -> "transport_error"
                is kotlinx.serialization.SerializationException -> "invalid_response"
                else -> "unexpected_error"
            }
            return failure(stage, reason)
        }
    }

    private fun viewed(model: JsonObject): Boolean =
        (model["viewedDate"] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true

    private fun accepted(reply: JsonObject, stage: String): Boolean {
        val success = (reply["success"] as? JsonPrimitive)?.booleanOrNull
        val abort = (reply["abort"] as? JsonPrimitive)?.booleanOrNull
        val connected = (reply["internetConnection"] as? JsonPrimitive)?.booleanOrNull
        if (success == true && abort != true && connected != false) return true
        val code = ((reply["errorContext"] as? JsonObject)?.get("errorCode") as? JsonPrimitive)?.intOrNull
        return failure(stage, "rejected success=$success abort=$abort internetConnection=$connected errorCode=$code")
    }

    private fun failure(stage: String, reason: String): Boolean {
        Log.w(TAG, "Read receipt failed stage=$stage reason=$reason")
        return false
    }

    private companion object { const val TAG = "GmcReadReceipt" }
}
