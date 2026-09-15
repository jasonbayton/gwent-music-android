package org.bayton.gmc.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens for the maintainer's own Bayton design language, defined here in full (MIT, no
 * third-party dependency). The accent (orange, #ff4500) is constant across light and dark. Two
 * slots are specific to this app:
 *  - `sink` (Home separation band) is a subtle raised strip rather than a recessed near-black.
 *  - `inkFaint`/`inkMute` (tertiary text): on dark they use #989086, the faintest warm grey that
 *    still clears 4.5:1 on every app background, so dark keeps a three-tier text hierarchy; on
 *    light they fall back to the dim tier, since the light canvas has no headroom for a fainter
 *    tone at 4.5:1.
 * On light, the accent foreground uses #c0360a because the brighter #ff6a33 fails contrast on the
 * light surfaces; on dark, accent2 stays #ff6a33.
 */
@Immutable
data class BaytonColors(
    val canvas: Color, val canvas2: Color, val sink: Color, val surface: Color, val surface2: Color,
    val line: Color, val line2: Color,
    val ink: Color, val inkDim: Color, val inkFaint: Color, val inkMute: Color,
    val accent: Color, val accent2: Color, val accentLo: Color, val accentSoft: Color, val accentLine: Color,
    val glow: Color,   // DS --glow: the ambient orange body-background radial (over canvas)
    val green: Color, val blue: Color, val red: Color,
    val isDark: Boolean,
)

// Dark theme.
val BaytonDark = BaytonColors(
    canvas = Color(0xFF131110), canvas2 = Color(0xFF1A1715), sink = Color(0xFF1A1715),
    surface = Color(0xFF201C19), surface2 = Color(0xFF2A2521),
    line = Color(0x1AF5EFE8), line2 = Color(0x2EF5EFE8),
    // #989086 is the faintest warm grey that still clears 4.5:1 on every app background here
    // (min ~4.66:1), so dark keeps a three-tier text hierarchy (ink > ink-dim > tertiary).
    ink = Color(0xFFF5EFE8), inkDim = Color(0xFFABA49B), inkFaint = Color(0xFF989086), inkMute = Color(0xFF989086),
    accent = Color(0xFFFF4500), accent2 = Color(0xFFFF6A33), accentLo = Color(0xFFC0360A),
    accentSoft = Color(0x21FF4500), accentLine = Color(0x66FF4500), glow = Color(0x1CFF4500),
    green = Color(0xFF15B007), blue = Color(0xFF0283BD), red = Color(0xFFC30000),
    isDark = true,
)

// Light theme (the accent2 slot uses the darker accent so accent foregrounds stay legible on the
// light surfaces).
val BaytonLight = BaytonColors(
    canvas = Color(0xFFE9E9EA), canvas2 = Color(0xFFF1F1F2), sink = Color(0xFFF1F1F2),
    surface = Color(0xFFFAFAFA), surface2 = Color(0xFFFFFFFF),
    line = Color(0x1C141416), line2 = Color(0x38141416),
    // A fainter tone fails 4.5:1 on the light surfaces, so faint and mute fall back to the dim tier.
    ink = Color(0xFF18181A), inkDim = Color(0xFF5A5A5E), inkFaint = Color(0xFF5A5A5E), inkMute = Color(0xFF5A5A5E),
    accent = Color(0xFFFF4500), accent2 = Color(0xFFC0360A), accentLo = Color(0xFFC0360A),
    accentSoft = Color(0x1AFF4500), accentLine = Color(0x6BFF4500), glow = Color(0x12FF4500),
    green = Color(0xFF15B007), blue = Color(0xFF0283BD), red = Color(0xFFC30000),
    isDark = false,
)

val LocalBayton = staticCompositionLocalOf { BaytonDark }
