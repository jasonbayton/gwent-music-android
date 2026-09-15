package org.bayton.gmc.ui.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ComposeKeyboardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun constrainedViewportKeepsSendVisibleAndFieldsReachable() {
        checkViewport(280, compact = false)
    }

    @Test fun landscapeKeyboardViewportKeepsBothEditorAndSendReachable() {
        checkViewport(150, compact = true)
    }

    private fun checkViewport(height: Int, compact: Boolean) {
        val vm = ComposeViewModel(mock(GwentRepository::class.java))
        compose.setContent {
            GwentMusicTheme {
                // Models the small viewport left above a landscape keyboard. Actual IME dispatch
                // and system-bar consumption are also checked on the physical Pixel.
                Box(Modifier.size(360.dp, height.dp)) {
                    ComposeMessageScreen("Subject", "teacher", "Teacher", {}, {}, vm)
                }
            }
        }
        val body = compose.onAllNodes(hasSetTextAction())[1]
        body.performScrollTo().performTextInput("A long draft\n".repeat(30))
        body.assertIsDisplayed()
        if (compact) compose.onNodeWithText("Send").performScrollTo()
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().assertIsDisplayed()
        if (compact) compose.onNodeWithText("Send").performScrollTo()
        compose.onNodeWithText("Send").assertIsDisplayed()
    }
}
