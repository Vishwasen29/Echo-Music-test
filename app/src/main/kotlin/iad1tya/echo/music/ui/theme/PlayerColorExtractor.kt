package iad1tya.echo.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PlayerColorExtractor {

    suspend fun extractGradientColors(
        palette: Palette,
        fallbackColor: Int,
    ): List<Color> = withContext(Dispatchers.Default) {
        val colorCandidates = listOfNotNull(
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.darkMutedSwatch,
        )

        val fallbackDominant = palette.dominantSwatch?.rgb?.let { Color(it) }
            ?: Color(palette.getDominantColor(fallbackColor))

        val primary = colorCandidates
            .maxByOrNull { calculateColorWeight(it) }
            ?.let { enhanceColorVividness(Color(it.rgb), if (isColorVibrant(Color(it.rgb))) 1.4f else 1.15f) }
            ?: enhanceColorVividness(fallbackDominant, 1.1f)

        listOf(
            primary,
            darkenColor(primary, 0.62f),
        )
    }

    suspend fun extractRichGradientColors(
        palette: Palette,
        fallbackColor: Int,
    ): List<Color> = withContext(Dispatchers.Default) {
        val dominant = palette.dominantSwatch?.let { Color(it.rgb) }
        val vibrant = palette.vibrantSwatch?.let { Color(it.rgb) }
        val darkVibrant = palette.darkVibrantSwatch?.let { Color(it.rgb) }
        val lightVibrant = palette.lightVibrantSwatch?.let { Color(it.rgb) }
        val muted = palette.mutedSwatch?.let { Color(it.rgb) }
        val darkMuted = palette.darkMutedSwatch?.let { Color(it.rgb) }

        val fallback = Color(fallbackColor)
        val primaryBase = vibrant ?: darkVibrant ?: dominant ?: muted ?: fallback
        val secondaryBase = lightVibrant ?: muted ?: darkMuted ?: dominant ?: primaryBase
        val depthBase = darkVibrant ?: darkMuted ?: dominant ?: primaryBase

        val primary = enhanceColorVividness(primaryBase, if (isColorVibrant(primaryBase)) 1.45f else 1.18f)
        val secondary = enhanceColorVividness(secondaryBase, if (isColorVibrant(secondaryBase)) 1.22f else 1.08f)
        val depth = darkenColor(depthBase, 0.68f)
        val floor = darkenColor(primary, 0.32f)

        listOf(
            primary.copy(alpha = 0.96f),
            secondary.copy(alpha = 0.86f),
            depth.copy(alpha = 0.96f),
            floor.copy(alpha = 1f),
        )
    }

    private fun isColorVibrant(color: Color): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        return saturation > 0.22f && brightness > 0.18f && brightness < 0.92f
    }

    private fun enhanceColorVividness(color: Color, saturationFactor: Float = 1.4f): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * saturationFactor).coerceIn(0.18f, 1.0f)
        hsv[2] = (hsv[2] * 0.92f).coerceIn(0.32f, 0.9f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun darkenColor(color: Color, factor: Float): Color {
        return Color(
            red = (color.red * factor).coerceIn(0f, 1f),
            green = (color.green * factor).coerceIn(0f, 1f),
            blue = (color.blue * factor).coerceIn(0f, 1f),
            alpha = color.alpha,
        )
    }

    private fun calculateColorWeight(swatch: Palette.Swatch?): Float {
        if (swatch == null) return 0f
        val population = swatch.population.toFloat()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(swatch.rgb, hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        val populationWeight = population * 2f
        val vibrancyBonus = if (saturation > 0.3f && brightness > 0.3f) 1.5f else 1f
        return populationWeight * vibrancyBonus * (saturation + brightness) / 2f
    }
}
