package iad1tya.echo.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import iad1tya.echo.music.R
import iad1tya.echo.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.max
import kotlin.math.min

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        @Volatile
        private var lastPlayedColor: Int = 0xFFFFFFFF.toInt()

        @Volatile
        private var lastTrackColor: Int = 0x55FFFFFF.toInt()

        private data class WidgetPalette(
            val background: Int,
            val foreground: Int,
            val secondaryText: Int,
        )

        fun updateWidget(
            context: Context,
            songTitle: String?,
            artistName: String?,
            albumArtUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            repeatMode: Int = Player.REPEAT_MODE_OFF,
            shuffleEnabled: Boolean = false,
            trackCounter: String? = null,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, MusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            MusicWidgetProvider().updateWidgets(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = appWidgetIds,
                songTitle = songTitle,
                artistName = artistName,
                albumArtUrl = albumArtUrl,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                trackCounter = trackCounter,
            )
        }

        fun updateProgress(
            context: Context,
            positionMs: Long,
            durationMs: Long,
            isPlaying: Boolean,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, MusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return

            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
            val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_music_player)
                applyProgress(views, progress.coerceIn(0, 1000))
                views.setImageViewResource(
                    R.id.widget_play_pause,
                    if (isPlaying) R.drawable.pause else R.drawable.play,
                )
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
        }

        private fun createProgressBitmap(
            width: Int,
            height: Int,
            progress: Float,
            playedColor: Int,
            trackColor: Int,
        ): Bitmap {
            val safeWidth = max(width, 240)
            val safeHeight = max(height, 18)
            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val left = safeHeight * 0.5f
            val right = safeWidth - safeHeight * 0.5f
            val centerY = safeHeight * 0.55f
            val clamped = progress.coerceIn(0f, 1f)
            val playedX = left + (right - left) * clamped

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = trackColor
                style = Paint.Style.STROKE
                strokeWidth = safeHeight * 0.18f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(left, centerY, right, centerY, trackPaint)

            val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = playedColor
                style = Paint.Style.STROKE
                strokeWidth = safeHeight * 0.18f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(left, centerY, playedX, centerY, playedPaint)

            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = playedColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(
                playedX.coerceIn(left, right),
                centerY,
                safeHeight * 0.18f,
                knobPaint,
            )

            return bitmap
        }

        private fun applyProgress(
            views: RemoteViews,
            progress: Int,
        ) {
            views.setImageViewBitmap(
                R.id.widget_progress_wave,
                createProgressBitmap(
                    width = 900,
                    height = 42,
                    progress = progress / 1000f,
                    playedColor = lastPlayedColor,
                    trackColor = lastTrackColor,
                ),
            )
        }

        private fun alpha(color: Int, alphaFraction: Float): Int {
            val a = (255 * alphaFraction.coerceIn(0f, 1f)).toInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        private fun isLight(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luma = (0.299 * r) + (0.587 * g) + (0.114 * b)
            return luma >= 168
        }

        private fun paletteFrom(bitmap: Bitmap): WidgetPalette {
            val palette = Palette.from(bitmap)
                .maximumColorCount(12)
                .resizeBitmapArea(120 * 120)
                .generate()

            val dominant = palette.getDarkVibrantColor(
                palette.getMutedColor(
                    palette.getDominantColor(0xFF121212.toInt())
                )
            )

            val foreground = if (isLight(dominant)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
            val secondary = alpha(foreground, 0.76f)

            lastPlayedColor = foreground
            lastTrackColor = alpha(foreground, 0.28f)

            return WidgetPalette(
                background = dominant,
                foreground = foreground,
                secondaryText = secondary,
            )
        }

        private fun getRoundedBitmap(bitmap: Bitmap, radiusPx: Float): Bitmap {
            val size = min(bitmap.width, bitmap.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val dstRect = Rect(0, 0, size, size)
            val dstRectF = RectF(dstRect)
            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            val srcRect = Rect(left, top, left + size, top + size)

            canvas.drawARGB(0, 0, 0, 0)
            paint.color = 0xff424242.toInt()
            canvas.drawRoundRect(dstRectF, radiusPx, radiusPx, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            paint.xfermode = null
            return output
        }

        private fun applyPalette(
            views: RemoteViews,
            palette: WidgetPalette,
            isPlaying: Boolean,
            repeatMode: Int,
            shuffleEnabled: Boolean,
        ) {
            views.setInt(R.id.widget_background, "setBackgroundColor", alpha(palette.background, 0.96f))
            views.setTextColor(R.id.widget_song_title, palette.foreground)
            views.setTextColor(R.id.widget_artist_name, palette.secondaryText)

            views.setInt(R.id.widget_shuffle, "setColorFilter", if (shuffleEnabled) palette.foreground else palette.secondaryText)
            views.setInt(R.id.widget_previous, "setColorFilter", palette.foreground)
            views.setInt(R.id.widget_next, "setColorFilter", palette.foreground)
            views.setInt(R.id.widget_repeat, "setColorFilter", if (repeatMode != Player.REPEAT_MODE_OFF) palette.foreground else palette.secondaryText)
            views.setInt(R.id.widget_play_pause, "setColorFilter", 0xFF111111.toInt())

            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.pause else R.drawable.play,
            )
            views.setImageViewResource(
                R.id.widget_shuffle,
                if (shuffleEnabled) R.drawable.shuffle_on else R.drawable.shuffle,
            )
            views.setImageViewResource(
                R.id.widget_repeat,
                when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                    Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                    else -> R.drawable.repeat
                },
            )
        }

        private fun applyCommonState(
            views: RemoteViews,
            context: Context,
            songTitle: String?,
            artistName: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            repeatMode: Int,
            shuffleEnabled: Boolean,
            trackCounter: String?,
        ) {
            views.setTextViewText(R.id.widget_song_title, songTitle ?: "No song playing")

            val subtitle = buildString {
                append(artistName ?: "Unknown artist")
                if (!trackCounter.isNullOrBlank()) {
                    append(" • ")
                    append(trackCounter)
                }
            }
            views.setTextViewText(R.id.widget_artist_name, subtitle)

            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
            val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0
            applyProgress(views, progress.coerceIn(0, 1000))

            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.pause else R.drawable.play,
            )
            views.setImageViewResource(
                R.id.widget_shuffle,
                if (shuffleEnabled) R.drawable.shuffle_on else R.drawable.shuffle,
            )
            views.setImageViewResource(
                R.id.widget_repeat,
                when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                    Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                    else -> R.drawable.repeat
                },
            )

            views.setOnClickPendingIntent(
                R.id.widget_shuffle,
                makeServicePendingIntent(context, 2001, MusicService.ACTION_TOGGLE_SHUFFLE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                makeServicePendingIntent(context, 2002, MusicService.ACTION_PLAY_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                makeServicePendingIntent(context, 2003, MusicService.ACTION_PREVIOUS),
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                makeServicePendingIntent(context, 2004, MusicService.ACTION_NEXT),
            )
            views.setOnClickPendingIntent(
                R.id.widget_repeat,
                makeServicePendingIntent(context, 2005, MusicService.ACTION_TOGGLE_REPEAT),
            )

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                2007,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root_click, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_song_info, openAppPendingIntent)

            applyPalette(
                views = views,
                palette = WidgetPalette(
                    background = 0xFF121212.toInt(),
                    foreground = 0xFFFFFFFF.toInt(),
                    secondaryText = 0xCCFFFFFF.toInt(),
                ),
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
            )
        }

        private fun makeServicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, MusicService::class.java).apply { this.action = action }
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            songTitle = null,
            artistName = null,
            albumArtUrl = null,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            trackCounter = null,
        )
    }

    private fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        songTitle: String?,
        artistName: String?,
        albumArtUrl: String?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        trackCounter: String?,
    ) {
        if (appWidgetIds.isEmpty()) return

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)
            applyCommonState(
                views = views,
                context = context,
                songTitle = songTitle,
                artistName = artistName,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                trackCounter = trackCounter,
            )
            views.setImageViewResource(R.id.widget_album_art, R.drawable.echo_logo)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        if (albumArtUrl.isNullOrBlank()) return

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val connection = URL(albumArtUrl).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    connect()
                }
                val rawBitmap = connection.getInputStream().use(BitmapFactory::decodeStream) ?: return@launch
                val scaled = if (rawBitmap.width > 768 || rawBitmap.height > 768) {
                    Bitmap.createScaledBitmap(rawBitmap, 768, 768, true)
                } else {
                    rawBitmap
                }
                val rounded = getRoundedBitmap(scaled, 28f)
                val palette = paletteFrom(scaled)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_music_player)
                        views.setImageViewBitmap(R.id.widget_album_art, rounded)
                        applyPalette(
                            views = views,
                            palette = palette,
                            isPlaying = isPlaying,
                            repeatMode = repeatMode,
                            shuffleEnabled = shuffleEnabled,
                        )
                        applyProgress(
                            views = views,
                            progress = if (durationMs > 0L) ((positionMs.coerceAtLeast(0L) * 1000L) / durationMs).toInt().coerceIn(0, 1000) else 0,
                        )
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
