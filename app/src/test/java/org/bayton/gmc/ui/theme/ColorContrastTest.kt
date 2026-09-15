package org.bayton.gmc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normal informative text requires 4.5:1, including small dates and field hints.
 * https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
 */
class ColorContrastTest {
    @Test fun `informative ink stays readable on actual dark and light backgrounds`() {
        for (theme in listOf(BaytonDark, BaytonLight)) {
            val backgrounds = mapOf(
                "canvas" to theme.canvas, "canvas2" to theme.canvas2, "sink" to theme.sink,
                "surface" to theme.surface, "surface2" to theme.surface2,
                // Test both ends of Home's monotonic warm accent gradient.
                "hero tint" to theme.accentSoft.compositeOver(theme.canvas),
                "read avatar" to theme.line.compositeOver(theme.canvas),
            )
            val inks = mapOf("ink" to theme.ink, "dim" to theme.inkDim,
                "faint" to theme.inkFaint, "muted" to theme.inkMute)
            for ((inkName, ink) in inks) for ((surfaceName, surface) in backgrounds) {
                val ratio = contrast(ink, surface)
                assertTrue("dark=${theme.isDark} $inkName on $surfaceName: $ratio < 4.5", ratio >= 4.5f)
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
