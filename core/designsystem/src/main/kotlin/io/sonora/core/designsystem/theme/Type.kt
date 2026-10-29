package io.sonora.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val SystemFont = FontFamily.Default

/**
 * Type scale sized like iOS Dynamic Type (default "Large") using the system font.
 *
 * Android's default sans (Roboto on many OEM skins) is lighter than SF Pro at the same nominal
 * weight, so primary row and body text uses Medium and keeps the font's own tracking instead of SF's tighter one.
 */
val SonoraTypography = Typography(
    // Large Title
    displaySmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.01.em),
    // Title 1 / 2 / 3
    headlineLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
    // Headline
    titleLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    // Body / Callout / Subheadline
    bodyLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // Caption 1 / 2
    labelLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp),
)
