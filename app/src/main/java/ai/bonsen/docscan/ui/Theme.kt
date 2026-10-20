package ai.bonsen.docscan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondaryContainer = Color(0xFFE3ECFF),
    onSecondaryContainer = Color(0xFF0B2A6B),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F3F6),
    onSurface = Color(0xFF14181F),
    onSurfaceVariant = Color(0xFF5B6472)
)

@Composable
fun DocScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
