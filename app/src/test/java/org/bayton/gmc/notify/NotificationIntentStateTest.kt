package org.bayton.gmc.notify

import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationIntentStateTest {
    private fun intent(route: String) = Intent().putExtra("route", route)

    @Test fun `cold start and warm repeated taps create consumable requests`() {
        val state = NotificationIntentState()
        state.restore(null, intent("schedule"))
        val first = state.pending.value!!
        assertEquals(1, first.tab)
        state.consume(first)
        assertNull(state.pending.value)
        state.accept(intent("schedule"))
        assertNotEquals(first.id, state.pending.value!!.id)
        state.accept(intent("messages"))
        assertEquals(2, state.pending.value!!.tab)
    }
    @Test fun `consuming an older event cannot erase a newer tap`() {
        val state = NotificationIntentState()
        state.accept(intent("schedule"))
        val first = state.pending.value!!
        state.accept(intent("messages"))
        state.consume(first)
        assertEquals(2, state.pending.value!!.tab)
    }
    @Test fun `pending route survives recreation and nonrouting login callback`() {
        val state = NotificationIntentState()
        state.accept(intent("messages"))
        state.accept(Intent())
        val saved = Bundle().also(state::save)
        val restored = NotificationIntentState()
        restored.restore(saved, Intent())
        assertEquals(2, restored.pending.value!!.tab)
    }
    @Test fun `consumed launch route does not replay on recreation`() {
        val state = NotificationIntentState()
        val launch = intent("messages")
        state.restore(null, launch)
        state.consume(state.pending.value!!)
        val saved = Bundle().also(state::save)
        val restored = NotificationIntentState()
        restored.restore(saved, launch)
        assertNull(restored.pending.value)
    }
    @Test fun `unknown destinations are ignored`() {
        val state = NotificationIntentState()
        state.accept(intent("compose?to=unexpected"))
        assertNull(state.pending.value)
    }
}
