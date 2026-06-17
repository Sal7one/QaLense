package com.qalens.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BeigeLightScheme = lightColorScheme(
    primary             = Color(0xFF1C1512),
    onPrimary           = Color(0xFFFAF8F5),
    primaryContainer    = Color(0xFFEDE8DF),
    onPrimaryContainer  = Color(0xFF1C1512),
    secondary           = Color(0xFF8B6914),
    onSecondary         = Color(0xFFFFFFFF),
    secondaryContainer  = Color(0xFFF5E6C0),
    onSecondaryContainer= Color(0xFF3D2A00),
    tertiary            = Color(0xFF1A4731),
    onTertiary          = Color(0xFFFFFFFF),
    background          = Color(0xFFFAF8F5),
    onBackground        = Color(0xFF1C1512),
    surface             = Color(0xFFFFFFFF),
    onSurface           = Color(0xFF1C1512),
    surfaceVariant      = Color(0xFFF0EDE8),
    onSurfaceVariant    = Color(0xFF6B6560),
    outline             = Color(0xFFCDC8C0),
    outlineVariant      = Color(0xFFE8E4DE),
    scrim               = Color(0xFF000000),
    inverseSurface      = Color(0xFF1C1512),
    inverseOnSurface    = Color(0xFFFAF8F5),
)

private val RichDarkScheme = darkColorScheme(
    primary             = Color(0xFFE2D9C8),
    onPrimary           = Color(0xFF1C1512),
    primaryContainer    = Color(0xFF2C2820),
    onPrimaryContainer  = Color(0xFFE2D9C8),
    secondary           = Color(0xFFDFBB6A),
    onSecondary         = Color(0xFF3D2A00),
    secondaryContainer  = Color(0xFF2E2000),
    onSecondaryContainer= Color(0xFFDFBB6A),
    tertiary            = Color(0xFF7DC9A3),
    onTertiary          = Color(0xFF003824),
    background          = Color(0xFF100E0B),
    onBackground        = Color(0xFFE8E4DC),
    surface             = Color(0xFF1A1714),
    onSurface           = Color(0xFFE8E4DC),
    surfaceVariant      = Color(0xFF24211C),
    onSurfaceVariant    = Color(0xFFA09890),
    outline             = Color(0xFF3D3830),
    outlineVariant      = Color(0xFF2C2820),
)

@Composable
fun SampleTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) RichDarkScheme else BeigeLightScheme,
        content = content
    )
}

// Credit-card gradients are intentionally theme-independent
object CardGradients {
    val navy     = listOf(Color(0xFF0F2847), Color(0xFF1D4E8F))
    val forest   = listOf(Color(0xFF0B3D22), Color(0xFF1A6B3D))
    val burgundy = listOf(Color(0xFF450B14), Color(0xFF7C1D2A))
    val purple   = listOf(Color(0xFF1E0A5C), Color(0xFF3D1A9E))

    fun forId(id: Int) = when (id % 4) {
        1 -> navy
        2 -> forest
        3 -> burgundy
        else -> purple
    }
}

object CategoryColors {
    private val map = mapOf(
        "Café"          to Color(0xFFB45309),
        "Groceries"     to Color(0xFF166534),
        "Entertainment" to Color(0xFF7C3AED),
        "Transfer"      to Color(0xFF1D4ED8),
        "Salary"        to Color(0xFF15803D),
        "Utilities"     to Color(0xFFD97706),
        "Shopping"      to Color(0xFFBE185D),
        "Fitness"       to Color(0xFFEA580C),
        "Dining"        to Color(0xFFD97706),
        "Transport"     to Color(0xFF0891B2),
        "Telecom"       to Color(0xFF4338CA),
        "Cash"          to Color(0xFF78716C),
        "Investment"    to Color(0xFF0D9488),
        "Books"         to Color(0xFF7C3AED),
    )
    fun of(category: String) = map[category] ?: Color(0xFF78716C)
}
