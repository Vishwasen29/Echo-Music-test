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
import iad1tya.echo.music.R
import iad1tya.echo.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateWidget(
            context: Context,
            songTitle: String?,
            artistName: String?,
            albumArtUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
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
                applyWaveformProgress(views, progress.coerceIn(0, 1000), isPlaying)
                views.setImageViewResource(
                    R.id.widget_play_pause,
                    if (isPlaying) R.drawable.pause else R.drawable.play,
                )
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
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
            trackCounter = null,
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

    private fun applyWaveformProgress(
        views: RemoteViews,
        progress: Int,
        isPlaying: Boolean,
    ) {
        val filled = 0xFFFFFFFF.toInt()
        val empty = if (isPlaying) 0x55FFFFFF else 0x33FFFFFF
        views.setImageViewBitmap(
            R.id.widget_progress_wave,
            createWaveformProgressBitmap(
                width = 720,
                height = 36,
                progress = progress / 1000f,
                filledColor = filled,
                emptyColor = empty,
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

    private fun applyCommonState(
        views: RemoteViews,
        context: Context,
        songTitle: String?,
        artistName: String?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
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
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) R.drawable.pause else R.drawable.play,
        )

        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
        val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0
        applyWaveformProgress(views, progress.coerceIn(0, 1000), isPlaying)

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
                val bitmap = connection.getInputStream().use(BitmapFactory::decodeStream) ?: return@launch
                val scaled = if (bitmap.width > 512 || bitmap.height > 512) {
                    Bitmap.createScaledBitmap(bitmap, 512, 512, true)
                } else {
                    bitmap
                }
                val rounded = getRoundedBitmap(scaled, 18f)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_music_player)
                        views.setImageViewBitmap(R.id.widget_album_art, rounded)
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
