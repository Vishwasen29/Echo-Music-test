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

class AdaptiveMusicWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val DEFAULT_BG = 0xFF111111.toInt()
        private const val INACTIVE_ICON = 0x88FFFFFF.toInt()
        private const val ACTIVE_ICON = 0xFFFFFFFF.toInt()
        private const val BACKGROUND_ALPHA = 178

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
            val componentName = ComponentName(context, AdaptiveMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            AdaptiveMusicWidgetProvider().updateWidgets(
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
            val componentName = ComponentName(context, AdaptiveMusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return

            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
            val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_music_player_adaptive)
                views.setProgressBar(R.id.widget_progress, 1000, progress.coerceIn(0, 1000), false)
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
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            trackCounter = null,
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
        views.setTextViewText(
            R.id.widget_artist_name,
            buildString {
                append(artistName ?: "Echo Music")
                if (!trackCounter.isNullOrBlank()) {
                    append(" • ")
                    append(trackCounter)
                }
            },
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) R.drawable.pause else R.drawable.play,
        )

        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, if (safeDuration > 0L) safeDuration else 0L)
        val progress = if (safeDuration > 0L) ((safePosition * 1000L) / safeDuration).toInt() else 0
        views.setProgressBar(R.id.widget_progress, 1000, progress.coerceIn(0, 1000), false)

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
            R.id.widget_previous,
            makeServicePendingIntent(context, 4101, MusicService.ACTION_PREVIOUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            makeServicePendingIntent(context, 4102, MusicService.ACTION_PLAY_PAUSE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            makeServicePendingIntent(context, 4103, MusicService.ACTION_NEXT),
        )
        views.setOnClickPendingIntent(
            R.id.widget_shuffle,
            makeServicePendingIntent(context, 4104, MusicService.ACTION_TOGGLE_SHUFFLE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_repeat,
            makeServicePendingIntent(context, 4105, MusicService.ACTION_TOGGLE_REPEAT),
        )

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            4106,
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
            val views = RemoteViews(context.packageName, R.layout.widget_music_player_adaptive)
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
                val artBitmap = getRoundedBitmap(scaled, 24f)
                val bgColor = chooseBackgroundColor(scaled)
                val bgBitmap = createRoundedBackgroundBitmap(1400, 620, bgColor, 42f)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_music_player_adaptive)
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
