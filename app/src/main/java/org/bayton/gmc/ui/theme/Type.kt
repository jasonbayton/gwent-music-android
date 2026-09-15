package org.bayton.gmc.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.bayton.gmc.R

private fun raleway(w: Int) = Font(R.font.raleway, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun openSans(w: Int) = Font(R.font.opensans, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

// Bayton type roles - Raleway display, Open Sans UI, monospace kicker.
val Display = FontFamily(raleway(500), raleway(600), raleway(700))
val Ui = FontFamily(openSans(400), openSans(500), openSans(600), openSans(700))
val Mono = FontFamily.Monospace

val BaytonTypography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp, lineHeight = 33.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Ui, fontWeight = FontWeight.SemiBold, fontSize = 14.5f.sp),
    bodyMedium = TextStyle(fontFamily = Ui, fontWeight = FontWeight.Normal, fontSize = 13.5f.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 2.4.sp),
)
