package io.github.zz9pa.amplifree.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AmplifreeDarkColorScheme = darkColorScheme(
    primary = AmpGold,
    onPrimary = AmpMetallicDark,
    primaryContainer = AmpGoldDark,
    onPrimaryContainer = AmpTextPrimary,
    secondary = AmpOrangeGlow,
    onSecondary = AmpMetallicDark,
    tertiary = AmpCyanLight,
    onTertiary = AmpMetallicDark,
    background = AmpMetallicDark,
    onBackground = AmpTextPrimary,
    surface = AmpSurface,
    onSurface = AmpTextPrimary,
    surfaceVariant = AmpSurfaceVariant,
    onSurfaceVariant = AmpTextSecondary
)

@Composable
fun AmplifreeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AmplifreeDarkColorScheme,
        typography = Typography,
        content = content
    )
}
