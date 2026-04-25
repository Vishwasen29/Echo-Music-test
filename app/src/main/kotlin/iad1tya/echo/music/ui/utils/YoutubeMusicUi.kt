package iad1tya.echo.music.ui.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object YtmUiColors {
    val Background = Color(0xFF050505)
    val Surface = Color(0xFF111111)
    val SurfaceHigh = Color(0xFF1D1D1D)
    val Red = Color(0xFFFF0033)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xCCFFFFFF)
    val TextMuted = Color(0x99FFFFFF)
}

@Composable
fun YtmBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFF160007), YtmUiColors.Background, Color.Black),
            ),
        ),
        content = content,
    )
}

@Composable
fun YtmSurfaceCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = YtmUiColors.Surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun YtmProviderChip(text: String, selected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) YtmUiColors.Red else YtmUiColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
fun YtmSectionHeader(title: String, subtitle: String? = null) {
    Text(text = title, color = YtmUiColors.TextPrimary, fontWeight = FontWeight.Bold)
    if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = subtitle, color = YtmUiColors.TextSecondary)
    }
}

@Composable
fun YtmTinyRedDot() {
    Spacer(
        modifier = Modifier
            .width(8.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(YtmUiColors.Red),
    )
}
