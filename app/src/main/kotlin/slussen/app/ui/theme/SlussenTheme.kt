package slussen.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's own dark theme, as the accepted mockups show it (the app-mirror tokens of the HTML mockups in `docs/mockups/`). */
object SlussenColors {
    val Background = Color(0xFF0B0E13)
    val Surface = Color(0xFF151A22)
    val Surface2 = Color(0xFF1D232E)
    val Line = Color(0xFF262C38)
    val Text = Color(0xFFE8ECF2)
    val Text2 = Color(0xFF9AA4B2)
    val Text3 = Color(0xFF6B7585)
    val Teal = Color(0xFF5FD4C4)
    val Amber = Color(0xFFD4A94F)
    val AmberBackground = Color(0xFF33270E)
    val Green = Color(0xFF6FCE8A)
    val Blue = Color(0xFF7AA7E8)
    val BlueBackground = Color(0xFF16202F)
    val Red = Color(0xFFE8806E)
    val RedBackground = Color(0xFF331713)
}

private val scheme = darkColorScheme(
    primary = SlussenColors.Blue,
    secondary = SlussenColors.Teal,
    tertiary = SlussenColors.Amber,
    background = SlussenColors.Background,
    surface = SlussenColors.Surface,
    surfaceVariant = SlussenColors.Surface2,
    onBackground = SlussenColors.Text,
    onSurface = SlussenColors.Text,
    onSurfaceVariant = SlussenColors.Text2,
    outline = SlussenColors.Line,
    error = SlussenColors.Red,
)

@Composable
fun SlussenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
