package org.bayton.gmc.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.bayton.gmc.data.model.SecureMessage
import org.bayton.gmc.ui.messages.MessageRow
import org.bayton.gmc.ui.messages.Segmented as MessageSegments
import org.bayton.gmc.ui.schedule.Segmented as ScheduleSegments
import org.bayton.gmc.ui.notifications.Segmented as ThemeSegments
import org.bayton.gmc.ui.theme.BaytonDark
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Render the production controls, so adding semantics to a test-only wrapper cannot pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccessibleStateTest {
    @get:Rule val compose = createComposeRule()

    private fun checkSelection(labels: List<String>, role: Role,
        content: @Composable (Int, (Int) -> Unit) -> Unit) {
        val selected = mutableIntStateOf(0)
        compose.setContent { GwentMusicTheme { content(selected.intValue) { selected.intValue = it } } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
        compose.onNodeWithText(labels.first()).assertIsSelected()
        labels.drop(1).forEach { compose.onNodeWithText(it).assertIsNotSelected() }
        compose.onNodeWithText(labels.last())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role)).performClick()
        compose.onNodeWithText(labels.last()).assertIsSelected()
        compose.onNodeWithText(labels.first()).assertIsNotSelected()
    }

    @Test fun `message tabs announce current selection after switching`() {
        val labels = listOf("Received", "Sent")
        checkSelection(labels, Role.Tab) { selected, choose -> MessageSegments(labels, selected, BaytonDark, choose) }
    }

    @Test fun `schedule tabs announce current selection after switching`() {
        val labels = listOf("Upcoming", "Past")
        checkSelection(labels, Role.Tab) { selected, choose -> ScheduleSegments(labels, selected, BaytonDark, choose) }
    }

    @Test fun `theme options announce current selection after switching`() {
        val labels = listOf("System", "Light", "Dark")
        checkSelection(labels, Role.RadioButton) { selected, choose -> ThemeSegments(labels, selected, BaytonDark, choose) }
    }

    private fun checkMessage(viewed: String?, sent: Boolean, expected: String?) {
        var opened = false
        compose.setContent {
            GwentMusicTheme {
                MessageRow(SecureMessage(subject = "Fixture message", viewedDate = viewed), sent, BaytonDark) { opened = true }
            }
        }
        val row = compose.onNodeWithText("Fixture message")
        row.assert(if (expected == null) SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
            else SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
        row.performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun `unread inbox row exposes unread status and remains clickable`() = checkMessage(null, false, "Unread")
    @Test fun `read inbox row exposes read status`() = checkMessage("read", false, "Read")
    @Test fun `sent message does not claim recipient read status`() = checkMessage(null, true, null)
}
