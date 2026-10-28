package vn.edu.uit.mobile.designsystem

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import vn.edu.uit.mobile.core.designsystem.R

object WorkspaceColors {
    val Ocean = Color(0xFF0875CF)
    val Teal = Color(0xFF1197B3)
    val Amber = Color(0xFFB97822)
    val Rose = Color(0xFFD75A77)
    val Sky = Color(0xFF3898E8)
    val Navy = Color(0xFF061426)
    fun course(id: Long): Color = listOf(Ocean, Color(0xFF227CB4), Color(0xFF188FA8), Sky)[((id % 4 + 4) % 4).toInt()]
}
val CampusSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold)
)
val CampusMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))
private val LightColors = lightColorScheme(
    primary = Color(0xFF0768B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEEFF), onPrimaryContainer = Color(0xFF064779),
    secondary = Color(0xFF087E99), secondaryContainer = Color(0xFFDDF2F7), onSecondaryContainer = Color(0xFF064C60),
    tertiary = Color(0xFF9F5A12), tertiaryContainer = Color(0xFFFFEDD6),
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF102A43),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF102A43),
    surfaceVariant = Color(0xFFE4EEF7), onSurfaceVariant = Color(0xFF5B6C7C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6FAFE),
    surfaceContainer = Color(0xFFEAF2FA), surfaceContainerHigh = Color(0xFFDFEBF5),
    outline = Color(0xFF7892A9), outlineVariant = Color(0xFFDCE3E9),
    error = Color(0xFFB73856), errorContainer = Color(0xFFFFEAF0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF65BEFF), onPrimary = Color(0xFF032442),
    primaryContainer = Color(0xFF103552), onPrimaryContainer = Color(0xFFC2E6FF),
    secondary = Color(0xFF63D5E4), secondaryContainer = Color(0xFF0D3444), onSecondaryContainer = Color(0xFFBDEFF5),
    tertiary = Color(0xFFF1BC70), tertiaryContainer = Color(0xFF3F301D),
    background = Color(0xFF111C28), onBackground = Color(0xFFE6F2FF),
    surface = Color(0xFF162330), onSurface = Color(0xFFE6F2FF),
    surfaceVariant = Color(0xFF122D47), onSurfaceVariant = Color(0xFFA0B0BF),
    surfaceContainerLowest = Color(0xFF07182B), surfaceContainerLow = Color(0xFF0B2036),
    surfaceContainer = Color(0xFF10283F), surfaceContainerHigh = Color(0xFF17334E),
    outline = Color(0xFF5B7894), outlineVariant = Color(0xFF2C3A47),
    error = Color(0xFFFFA0B6), errorContainer = Color(0xFF412536)
)
private val WorkspaceTypography = Typography(
    displaySmall = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = CampusSans, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = CampusSans, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = CampusSans, fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = CampusSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = CampusSans, fontSize = 11.sp, lineHeight = 16.sp)
)

@Composable
fun UitTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = uitColorScheme(context, dark, dynamic)
    MaterialTheme(
        colorScheme = colors,
        typography = WorkspaceTypography,
        shapes = Shapes(extraSmall = TechnicalShapes.Tile, small = TechnicalShapes.Control, medium = TechnicalShapes.Field, large = TechnicalShapes.Panel, extraLarge = TechnicalShapes.Dialog),
        content = content
    )
}

fun uitColorScheme(context: Context, dark: Boolean, dynamic: Boolean): ColorScheme = when {
    dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> DarkColors
    else -> LightColors
}
