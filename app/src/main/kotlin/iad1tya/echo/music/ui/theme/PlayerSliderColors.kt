package iad1tya.echo.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import iad1tya.echo.music.constants.PlayerBackgroundStyle

object PlayerSliderColors {

    @Composable
    private fun activeColorForBackground(
        buttonColor: Color,
        playerBackground: PlayerBackgroundStyle,
        useDarkTheme: Boolean,
    ): Color {
        return when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> if (useDarkTheme) Color.White else buttonColor
            PlayerBackgroundStyle.GRADIENT,
            PlayerBackgroundStyle.BLUR,
            PlayerBackgroundStyle.GLOW_ANIMATED -> Color.White
        }
    }

    @Composable
    private fun inactiveColorForBackground(
        playerBackground: PlayerBackgroundStyle,
        useDarkTheme: Boolean,
    ): Color {
        return when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> {
                if (useDarkTheme) {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                }
            }

            PlayerBackgroundStyle.GRADIENT,
            PlayerBackgroundStyle.BLUR,
            PlayerBackgroundStyle.GLOW_ANIMATED -> Color.White.copy(alpha = 0.32f)
        }
    }

    @Composable
    fun defaultSliderColors(
        buttonColor: Color,
        playerBackground: PlayerBackgroundStyle,
        useDarkTheme: Boolean,
    ): SliderColors {
        val active = activeColorForBackground(buttonColor, playerBackground, useDarkTheme)
        val inactive = inactiveColorForBackground(playerBackground, useDarkTheme)
        return SliderDefaults.colors(
            activeTrackColor = active,
            activeTickColor = active,
            thumbColor = active,
            inactiveTrackColor = inactive,
            inactiveTickColor = inactive,
        )
    }

    @Composable
    fun squigglySliderColors(
        buttonColor: Color,
        playerBackground: PlayerBackgroundStyle,
        useDarkTheme: Boolean,
    ): SliderColors = defaultSliderColors(buttonColor, playerBackground, useDarkTheme)

    @Composable
    fun slimSliderColors(
        buttonColor: Color,
        playerBackground: PlayerBackgroundStyle,
        useDarkTheme: Boolean,
    ): SliderColors {
        val active = activeColorForBackground(buttonColor, playerBackground, useDarkTheme)
        val inactive = inactiveColorForBackground(playerBackground, useDarkTheme)
        return SliderDefaults.colors(
            activeTrackColor = active,
            activeTickColor = active,
            thumbColor = Color.Transparent,
            inactiveTrackColor = inactive,
            inactiveTickColor = inactive,
        )
    }
}
