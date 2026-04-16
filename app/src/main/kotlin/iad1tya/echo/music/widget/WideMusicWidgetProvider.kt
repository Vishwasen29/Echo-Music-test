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

class WideMusicWidgetProvider : AppWidgetProvider() {

    companion object {
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


    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetPlaybackController.handleReceive(context, intent)) return
        super.onReceive(context, intent)
    }
    private fun makeServicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        return WidgetPlaybackController.createPendingIntent(context, WideMusicWidgetProvider::class.java, requestCode, action)
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

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val connection = URL(albumArtUrl).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    connect()
                }
                val bitmap = connection.getInputStream().use(BitmapFactory::decodeStream) ?: return@launch
                val rounded = getRoundedBitmap(bitmap, 18f)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_music_player_4x1)
                        views.setImageViewBitmap(R.id.widget_album_art, rounded)
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
