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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import iad1tya.echo.music.R
import iad1tya.echo.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class ScalableMusicWidgetProvider : AppWidgetProvider() {

    companion object {

        private fun createWaveformProgressBitmap(
            width: Int,
            height: Int,
            progress: Float,
            playedColor: Int,
            trackColor: Int,
            knobColor: Int,
        ): Bitmap {
            val safeWidth = width.coerceAtLeast(160)
            val safeHeight = height.coerceAtLeast(24)
            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val left = safeHeight * 0.65f
            val right = safeWidth - safeHeight * 0.65f
            val centerY = safeHeight * 0.58f
            val clamped = progress.coerceIn(0f, 1f)
            val playedX = left + (right - left) * clamped

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = trackColor
                style = Paint.Style.STROKE
                strokeWidth = safeHeight * 0.16f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(left, centerY, right, centerY, trackPaint)

            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = playedColor
                style = Paint.Style.STROKE
                strokeWidth = safeHeight * 0.16f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val waveWidth = (playedX - left).coerceAtLeast(0f)
            if (waveWidth > 4f) {
                val path = android.graphics.Path()
                val amplitude = safeHeight * 0.12f
                val cycles = kotlin.math.max(1, (waveWidth / (safeHeight * 1.9f)).toInt())
                val steps = kotlin.math.max(18, cycles * 28)
                path.moveTo(left, centerY)
                for (step in 1..steps) {
                    val t = step / steps.toFloat()
                    val x = left + waveWidth * t
                    val y = centerY + kotlin.math.sin(t * cycles * 2.0 * kotlin.math.PI).toFloat() * amplitude
                    path.lineTo(x, y)
                }
                canvas.drawPath(path, wavePaint)
            }

            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = knobColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(
                playedX.coerceIn(left, right),
                centerY,
                safeHeight * 0.23f,
                knobPaint,
            )

            return bitmap
        }

        private fun applySquiggleProgress(
            views: RemoteViews,
            progress: Int,
            isPlaying: Boolean,
        ) {
            val played = ACTIVE_ICON
            val track = if (isPlaying) 0x66FFFFFF else 0x40FFFFFF
            val knob = ACTIVE_ICON
            views.setImageViewBitmap(
                R.id.widget_progress_wave,
                createWaveformProgressBitmap(
                    width = 900,
                    height = 44,
                    progress = progress / 1000f,
                    playedColor = played,
                    trackColor = track,
                    knobColor = knob,
                ),
            )
        }


        private const val DEFAULT_BG = 0xFF111111.toInt()
        private const val INACTIVE_ICON = 0x88FFFFFF.toInt()
        private const val ACTIVE_ICON = 0xFFFFFFFF.toInt()
        private const val BACKGROUND_ALPHA = 179

        fun updateWidget(
            context: Context,
            songTitle: String?,
            artistName: String?,
            albumArtUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            repeatMode: Int,
            shuffleEnabled: Boolean,
            trackCounter: String? = null,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, ScalableMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            ScalableMusicWidgetProvider().updateWidgets(
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
            val componentName = ComponentName(context, ScalableMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return

            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
            val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_music_player_large)
                applySquiggleProgress(views, progress.coerceIn(0, 1000), isPlaying)
                views.setImageViewResource(
                    R.id.widget_play_pause,
                    if (isPlaying) R.drawable.pause else R.drawable.play,
                )
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetPlaybackController.handleReceive(context, intent)) return
        super.onReceive(context, intent)
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
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            trackCounter = null,
        )
    }


    private fun makeServicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        return WidgetPlaybackController.createPendingIntent(context, ScalableMusicWidgetProvider::class.java, requestCode, action)
    }


    // ECHO_FIX_SQUIGGLE_WIDGET
    private fun createSquiggleProgressBitmap(
        width: Int,
        height: Int,
        progress: Float,
        playedColor: Int,
        restColor: Int,
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(240)
        val safeHeight = height.coerceAtLeast(18)
        val bmp = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val centerY = safeHeight / 2f
        val amplitude = safeHeight * 0.18f
        val playedEndX = (safeWidth * progress.coerceIn(0f, 1f)).coerceIn(0f, safeWidth.toFloat())

        val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = restColor
            style = Paint.Style.STROKE
            strokeWidth = (safeHeight * 0.18f).coerceAtLeast(3f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawLine(0f, centerY, safeWidth.toFloat(), centerY, restPaint)

        val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = playedColor
            style = Paint.Style.STROKE
            strokeWidth = (safeHeight * 0.22f).coerceAtLeast(4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        if (playedEndX > 0f) {
            val path = android.graphics.Path()
            val cycles = 2.4f
            val segments = 64
            for (i in 0..segments) {
                val t = i / segments.toFloat()
                val x = playedEndX * t
                val y = centerY + kotlin.math.sin(t * cycles * Math.PI * 2.0).toFloat() * amplitude
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, playedPaint)

            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = playedColor }
            canvas.drawCircle(playedEndX, centerY, safeHeight * 0.23f, knobPaint)
        }

        return bmp
    }

    private fun applySquiggleProgress(
        views: RemoteViews,
        progress: Int,
        isPlaying: Boolean,
    ) {
        val played = ACTIVE_ICON
        val rest = if (isPlaying) 0x55FFFFFF else 0x33FFFFFF
        views.setImageViewBitmap(
            R.id.widget_progress_wave,
            createSquiggleProgressBitmap(
                width = 720,
                height = 36,
                progress = progress / 1000f,
                playedColor = played,
                restColor = rest,
            ),
        )
    }

    private fun getRoundedBitmap(bitmap: Bitmap, radiusPx: Float): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dstRect = Rect(0, 0, size, size)
        val dstRectF = RectF(dstRect)
        val srcLeft = (bitmap.width - size) / 2
        val srcTop = (bitmap.height - size) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + size, srcTop + size)

        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.WHITE
        canvas.drawRoundRect(dstRectF, radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        paint.xfermode = null
        return output
    }

    private fun createRoundedBackgroundBitmap(width: Int, height: Int, color: Int, radiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, paint)
        return output
    }


    private fun createWaveformProgressBitmap(
        width: Int,
        height: Int,
        progress: Float,
        filledColor: Int,
        emptyColor: Int,
        bars: Int = 28,
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(120)
        val safeHeight = height.coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = filledColor }
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = emptyColor }

        val clamped = progress.coerceIn(0f, 1f)
        val gap = (safeWidth * 0.008f).coerceAtLeast(2f)
        val totalGap = gap * (bars - 1)
        val barWidth = ((safeWidth - totalGap) / bars.toFloat()).coerceAtLeast(4f)
        val filledBars = clamped * bars

        for (i in 0 until bars) {
            val left = i * (barWidth + gap)
            val right = left + barWidth
            val normalized = if (bars <= 1) 1f else i / (bars - 1f)
            val edgeBoost = kotlin.math.abs(0.5f - normalized) * 1.2f
            val base = (0.22f + edgeBoost).coerceAtMost(0.78f)
            val dynamic = when (i % 4) {
                0 -> 0.95f
                1 -> 0.60f
                2 -> 0.82f
                else -> 0.48f
            }
            val barHeight = (safeHeight * (0.35f + base * dynamic)).coerceAtLeast(safeHeight * 0.32f)
            val top = (safeHeight - barHeight) / 2f
            val rect = RectF(left, top, right, top + barHeight)
            val paint = if (i + 1 <= filledBars) filledPaint else emptyPaint
            val radius = (barWidth / 2f).coerceAtMost(12f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        return bmp
    }

    private fun chooseBackgroundColor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).clearFilters().generate()
        return palette.getDarkVibrantColor(
            palette.getVibrantColor(
                palette.getDarkMutedColor(
                    palette.getDominantColor(DEFAULT_BG),
                ),
            ),
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
        views.setTextViewText(R.id.widget_song_title, songTitle ?: "Nothing playing")
        views.setTextViewText(R.id.widget_artist_name, artistName ?: "Echo Music")
        views.setTextViewText(R.id.widget_track_counter, trackCounter ?: "")
        views.setViewVisibility(
            R.id.widget_track_counter,
            if (trackCounter.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE,
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) R.drawable.pause else R.drawable.play,
        )

        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
        val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0
        applySquiggleProgress(views, progress.coerceIn(0, 1000), isPlaying)

        val repeatRes = when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
            androidx.media3.common.Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
            else -> R.drawable.repeat
        }
        views.setImageViewResource(R.id.widget_repeat, repeatRes)
        views.setInt(
            R.id.widget_repeat,
            "setColorFilter",
            if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) INACTIVE_ICON else ACTIVE_ICON,
        )
        views.setInt(
            R.id.widget_shuffle,
            "setColorFilter",
            if (shuffleEnabled) ACTIVE_ICON else INACTIVE_ICON,
        )

        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            makeServicePendingIntent(context, 3002, MusicService.ACTION_PLAY_PAUSE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_previous,
            makeServicePendingIntent(context, 3003, MusicService.ACTION_PREVIOUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            makeServicePendingIntent(context, 3004, MusicService.ACTION_NEXT),
        )
        views.setOnClickPendingIntent(
            R.id.widget_shuffle,
            makeServicePendingIntent(context, 3005, MusicService.ACTION_TOGGLE_SHUFFLE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_repeat,
            makeServicePendingIntent(context, 3006, MusicService.ACTION_TOGGLE_REPEAT),
        )

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            3007,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root_click, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_song_info, openAppPendingIntent)
        views.setInt(R.id.widget_background_tint, "setImageAlpha", BACKGROUND_ALPHA)
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
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_large)
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
            views.setImageViewResource(R.id.widget_background_tint, R.drawable.widget_background)
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
                val bitmap = connection.getInputStream().use(BitmapFactory::decodeStream) ?: return@launch
                val scaled = if (bitmap.width > 768 || bitmap.height > 768) {
                    Bitmap.createScaledBitmap(bitmap, 768, 768, true)
                } else {
                    bitmap
                }
                val artBitmap = getRoundedBitmap(scaled, 28f)
                val bgColor = chooseBackgroundColor(scaled)
                val bgBitmap = createRoundedBackgroundBitmap(1400, 620, bgColor, 40f)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_music_player_large)
                        views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
                        views.setImageViewBitmap(R.id.widget_background_tint, bgBitmap)
                        views.setInt(R.id.widget_background_tint, "setImageAlpha", BACKGROUND_ALPHA)
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
