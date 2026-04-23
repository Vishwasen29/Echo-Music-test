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
import androidx.palette.graphics.Palette
import iad1tya.echo.music.R
import iad1tya.echo.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class WideMusicWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val DEFAULT_BG = 0xFF111111.toInt()
        private const val BACKGROUND_ALPHA = 228
        private val widgetCacheLock = Any()

        @Volatile
        private var cachedAlbumArtUrl: String? = null
        private var cachedRoundedAlbumArt: Bitmap? = null
        private var cachedBackgroundBitmap: Bitmap? = null

        fun updateWidget(
            context: Context,
            songTitle: String?,
            artistName: String?,
            albumArtUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WideMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            WideMusicWidgetProvider().updateWidgets(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = appWidgetIds,
                songTitle = songTitle,
                artistName = artistName,
                albumArtUrl = albumArtUrl,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
            )
        }

        fun updateProgress(
            context: Context,
            positionMs: Long,
            durationMs: Long,
            isPlaying: Boolean,
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, WideMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return

            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
            val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_music_player_4x1)
                views.setProgressBar(R.id.widget_progress, 1000, progress.coerceIn(0, 1000), false)
                views.setImageViewResource(
                    R.id.widget_play_pause,
                    if (isPlaying) R.drawable.pause else R.drawable.play,
                )
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
        }

        private fun getCachedVisuals(albumArtUrl: String): Pair<Bitmap, Bitmap>? = synchronized(widgetCacheLock) {
            val cachedUrl = cachedAlbumArtUrl
            val cachedArt = cachedRoundedAlbumArt
            val cachedBackground = cachedBackgroundBitmap
            if (cachedUrl == albumArtUrl && cachedArt != null && cachedBackground != null) {
                cachedArt to cachedBackground
            } else {
                null
            }
        }

        private fun putCachedVisuals(albumArtUrl: String, roundedArt: Bitmap, backgroundBitmap: Bitmap) {
            synchronized(widgetCacheLock) {
                cachedAlbumArtUrl = albumArtUrl
                cachedRoundedAlbumArt = roundedArt
                cachedBackgroundBitmap = backgroundBitmap
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
        )
    }

    private fun makeServicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
        paint.color = 0xff424242.toInt()
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

    private fun scaleBitmapForWidget(bitmap: Bitmap, maxSide: Int = 320): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest.toFloat()
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun applyCommonState(
        views: RemoteViews,
        context: Context,
        songTitle: String?,
        artistName: String?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        views.setTextViewText(R.id.widget_song_title, songTitle ?: "No song playing")
        views.setTextViewText(R.id.widget_artist_name, artistName ?: "Unknown artist")
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) R.drawable.pause else R.drawable.play,
        )
        views.setImageViewResource(R.id.widget_background_tint, R.drawable.widget_background)
        views.setInt(R.id.widget_background_tint, "setImageAlpha", BACKGROUND_ALPHA)

        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
        val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0
        views.setProgressBar(R.id.widget_progress, 1000, progress.coerceIn(0, 1000), false)

        views.setOnClickPendingIntent(
            R.id.widget_previous,
            makeServicePendingIntent(context, 5101, MusicService.ACTION_PREVIOUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            makeServicePendingIntent(context, 5102, MusicService.ACTION_PLAY_PAUSE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            makeServicePendingIntent(context, 5103, MusicService.ACTION_NEXT),
        )

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            5104,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root_click, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_song_info, openAppPendingIntent)
    }

    private fun applyArtworkAndPalette(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        roundedAlbumArt: Bitmap,
        backgroundBitmap: Bitmap,
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_4x1)
            views.setImageViewBitmap(R.id.widget_album_art, roundedAlbumArt)
            views.setImageViewBitmap(R.id.widget_background_tint, backgroundBitmap)
            views.setInt(R.id.widget_background_tint, "setImageAlpha", BACKGROUND_ALPHA)
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
        }
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
    ) {
        if (appWidgetIds.isEmpty()) return

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_4x1)
            applyCommonState(
                views = views,
                context = context,
                songTitle = songTitle,
                artistName = artistName,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
            )
            views.setImageViewResource(R.id.widget_album_art, R.drawable.echo_logo)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        if (albumArtUrl.isNullOrBlank()) return

        getCachedVisuals(albumArtUrl)?.let { (cachedArt, cachedBackground) ->
            applyArtworkAndPalette(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = appWidgetIds,
                roundedAlbumArt = cachedArt,
                backgroundBitmap = cachedBackground,
            )
            return
        }

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val connection = URL(albumArtUrl).openConnection().apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    connect()
                }
                val bitmap = connection.getInputStream().use(BitmapFactory::decodeStream) ?: return@launch
                val scaled = scaleBitmapForWidget(bitmap)
                val rounded = getRoundedBitmap(scaled, 18f)
                val bgColor = chooseBackgroundColor(scaled)
                val bgBitmap = createRoundedBackgroundBitmap(1200, 240, bgColor, 32f)
                putCachedVisuals(albumArtUrl, rounded, bgBitmap)

                withContext(Dispatchers.Main) {
                    applyArtworkAndPalette(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        appWidgetIds = appWidgetIds,
                        roundedAlbumArt = rounded,
                        backgroundBitmap = bgBitmap,
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
