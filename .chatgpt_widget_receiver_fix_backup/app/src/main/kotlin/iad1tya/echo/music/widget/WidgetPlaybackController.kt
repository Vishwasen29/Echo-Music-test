package iad1tya.echo.music.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import iad1tya.echo.music.playback.MusicService

object WidgetPlaybackController {
    private const val TAG = "WidgetPlaybackCtrl"

    fun createPendingIntent(
        context: Context,
        providerClass: Class<*>,
        requestCode: Int,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, providerClass).apply {
            this.action = action
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun handleReceive(context: Context, intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return when (action) {
            MusicService.ACTION_PLAY_PAUSE,
            MusicService.ACTION_NEXT,
            MusicService.ACTION_PREVIOUS,
            MusicService.ACTION_TOGGLE_SHUFFLE,
            MusicService.ACTION_TOGGLE_REPEAT,
            -> {
                dispatch(context, action)
                true
            }
            else -> false
        }
    }

    private fun dispatch(context: Context, action: String) {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    try {
                        when (action) {
                            MusicService.ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                            MusicService.ACTION_NEXT -> controller.seekToNextMediaItem()
                            MusicService.ACTION_PREVIOUS -> controller.seekToPreviousMediaItem()
                            MusicService.ACTION_TOGGLE_SHUFFLE -> controller.shuffleModeEnabled = !controller.shuffleModeEnabled
                            MusicService.ACTION_TOGGLE_REPEAT -> {
                                controller.repeatMode = when (controller.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        }
                    } finally {
                        controller.release()
                    }
                }.onFailure {
                    Log.e(TAG, "Widget action failed: $action", it)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }
}
