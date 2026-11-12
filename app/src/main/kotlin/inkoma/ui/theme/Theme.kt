package inkoma.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import inkoma.core.ThemeMode

/** Mihon's default ("Tachiyomi") Material 3 color scheme, copied from TachiyomiColorScheme.kt. */
object TachiyomiColorScheme {
    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFB0C6FF),
        onPrimary = Color(0xFF002D6E),
        primaryContainer = Color(0xFF00429B),
        onPrimaryContainer = Color(0xFFD9E2FF),
        inversePrimary = Color(0xFF0058CA),
        secondary = Color(0xFFB0C6FF),
        onSecondary = Color(0xFF002D6E),
        secondaryContainer = Color(0xFF00429B),
        onSecondaryContainer = Color(0xFFD9E2FF),
        tertiary = Color(0xFF7ADC77),
        onTertiary = Color(0xFF003909),
        tertiaryContainer = Color(0xFF005312),
        onTertiaryContainer = Color(0xFF95F990),
        background = Color(0xFF1B1B1F),
        onBackground = Color(0xFFE3E2E6),
        surface = Color(0xFF1B1B1F),
        onSurface = Color(0xFFE3E2E6),
        surfaceVariant = Color(0xFF211F26),
        onSurfaceVariant = Color(0xFFC5C6D0),
        surfaceTint = Color(0xFFB0C6FF),
        inverseSurface = Color(0xFFE3E2E6),
        inverseOnSurface = Color(0xFF1B1B1F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8F9099),
        outlineVariant = Color(0xFF44464F),
        surfaceContainerLowest = Color(0xFF1A181D),
        surfaceContainerLow = Color(0xFF1E1C22),
        surfaceContainer = Color(0xFF211F26),
        surfaceContainerHigh = Color(0xFF292730),
        surfaceContainerHighest = Color(0xFF302E38),
    )

    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF0058CA),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD9E2FF),
        onPrimaryContainer = Color(0xFF001945),
        inversePrimary = Color(0xFFB0C6FF),
        secondary = Color(0xFF0058CA),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD9E2FF),
        onSecondaryContainer = Color(0xFF001945),
        tertiary = Color(0xFF006E1B),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF95F990),
        onTertiaryContainer = Color(0xFF002203),
        background = Color(0xFFFEFBFF),
        onBackground = Color(0xFF1B1B1F),
        surface = Color(0xFFFEFBFF),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = Color(0xFFF3EDF7),
        onSurfaceVariant = Color(0xFF44464F),
        surfaceTint = Color(0xFF0058CA),
        inverseSurface = Color(0xFF303034),
        inverseOnSurface = Color(0xFFF2F0F4),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF757780),
        outlineVariant = Color(0xFFC5C6D0),
        surfaceContainerLowest = Color(0xFFF5F1F8),
        surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF3EDF7),
        surfaceContainerHigh = Color(0xFFFCF7FF),
        surfaceContainerHighest = Color(0xFFFCF7FF),
    )
}

private fun font(name: String, weight: FontWeight) =
    Font(resource = "font/$name", weight = weight)

/** Roboto, bundled so text renders identically to Android regardless of system fonts. */
val Roboto: FontFamily = FontFamily(
    font("Roboto-Regular.ttf", FontWeight.Normal),
    font("Roboto-Medium.ttf", FontWeight.Medium),
    font("Roboto-Bold.ttf", FontWeight.Bold),
)

private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private val InkomaTypography = Typography().withFamily(Roboto)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun InkomaTheme(
    mode: ThemeMode,
    amoled: Boolean,
    appTheme: AppTheme = AppTheme.TACHIYOMI,
    customSeed: Int = 0xFF0058CA.toInt(),
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)
    val baseScheme = if (dark) TachiyomiColorScheme.dark else TachiyomiColorScheme.light
    val themed = ThemeSchemes.scheme(appTheme, dark) ?: SeedColors.scheme(Color(customSeed), dark, baseScheme)
    val scheme = if (dark && amoled) {
        themed.copy(background = Color.Black, onBackground = Color.White, surface = Color.Black, onSurface = Color.White)
    } else {
        themed
    }
    MaterialTheme(colorScheme = scheme, typography = InkomaTypography, content = content)
}
