package org.bayton.gmc.ui.messages

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class ComposeDraftTest {
    @get:Rule val compose = createComposeRule()
    private val vm = ComposeViewModel(mock(GwentRepository::class.java))

    @Test fun editedSubjectAndBodySurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            GwentMusicTheme { ComposeMessageScreen("Re: Lesson", "teacher", "Teacher", {}, {}, vm) }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Changed subject")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("First line\nSecond line")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("Changed subject")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("First line\nSecond line")
    }

    @Test fun freshDestinationDoesNotInheritAnotherDraft() {
        val destination = mutableStateOf(1)
        compose.setContent {
            key(destination.value) {
                GwentMusicTheme { ComposeMessageScreen("", "teacher", "Teacher", {}, {}, vm) }
            }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Private draft")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("Private body")
        compose.runOnIdle { destination.value = 2 }
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("")
    }
}
