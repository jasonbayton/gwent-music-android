package org.bayton.gmc.ui.schedule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleFooterDoesNotLoadUntilClicked() {
        var requests = 0
        compose.setContent {
            GwentMusicTheme(darkTheme = true) {
                HistoryContent(HistoryUi(loading = false, loadedFrom = LocalDate.of(2025, 1, 1)),
                    onBack = {}, loadMore = { requests++ })
            }
        }
        compose.onNodeWithText("Load more").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithText("Load more").performClick()
        compose.waitForIdle()
        assertEquals(1, requests)
    }
}
