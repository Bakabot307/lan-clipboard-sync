package com.gnaht.phoneclipboardsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Neutral100,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Teal30,
    onSecondary = Neutral100,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,
    tertiary = Amber30,
    onTertiary = Neutral100,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber30,
    background = Neutral96,
    onBackground = Neutral10,
    surface = Neutral100,
    onSurface = Neutral10,
    surfaceVariant = Neutral94,
    onSurfaceVariant = NeutralVar30,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
    outline = NeutralVar50,
    outlineVariant = NeutralVar80,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Indigo80,
    error = StatusRed,
    onError = Neutral100,
    errorContainer = StatusRedLight,
    onErrorContainer = StatusRed,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,
    tertiary = Amber80,
    onTertiary = Amber30,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral30,
    onSurfaceVariant = NeutralVar80,
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    outline = NeutralVar60,
    outlineVariant = NeutralVar30,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Indigo40,
    error = StatusRedDark,
    onError = Neutral10,
    errorContainer = StatusRedDarkContainer,
    onErrorContainer = StatusRedDark,
)

@Composable
fun LanClipboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
