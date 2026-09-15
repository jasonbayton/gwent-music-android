package org.bayton.gmc.ui.schedule

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.ui.theme.BaytonDark
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduleRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedAndMissingDiaryIdsRenderEveryPupil() {
        val rows = listOf("Alex", "Sam", "Robin", "Casey").mapIndexed { index, name ->
            DiaryEntry(diaryID = if (index < 2) "shared" else null,
                firstName = name, subject = "Brass", startDateTime = "2026-09-10T08:00:00")
        }
        compose.setContent {
            GwentMusicTheme(darkTheme = true) {
                LazyColumn { agenda(rows, BaytonDark, true, false, "test-term") }
            }
        }
        rows.forEach { compose.onNodeWithText(it.firstName!!.uppercase()).assertIsDisplayed() }
    }
}
