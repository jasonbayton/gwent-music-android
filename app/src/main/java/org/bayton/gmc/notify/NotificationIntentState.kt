package org.bayton.gmc.notify

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationRouteRequest(val id: Long, val tab: Int)

/** A consumable request, not a permanent start-tab preference. Survives activity recreation. */
class NotificationIntentState {
    private var sequence = 0L
    private val _pending = MutableStateFlow<NotificationRouteRequest?>(null)
    val pending = _pending.asStateFlow()

    fun accept(intent: Intent?) {
        val tab = when (intent?.getStringExtra("route")) {
            "messages" -> 2
            "schedule" -> 1
            else -> return // ordinary OAuth/launcher intents must not discard an outstanding request
        }
        _pending.value = NotificationRouteRequest(++sequence, tab)
    }

    fun consume(request: NotificationRouteRequest) {
        if (_pending.value == request) _pending.value = null
    }

    fun restore(saved: Bundle?, launch: Intent?) {
        if (saved == null || !saved.containsKey(KEY)) accept(launch)
        else saved.getInt(KEY).takeIf { it == 1 || it == 2 }?.let {
            _pending.value = NotificationRouteRequest(++sequence, it)
        }
    }

    fun save(out: Bundle) { out.putInt(KEY, _pending.value?.tab ?: -1) }

    private companion object { const val KEY = "pending_notification_tab" }
}
