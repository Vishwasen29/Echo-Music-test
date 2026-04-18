package iad1tya.echo.music.lyrics

import android.content.Context
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.utils.NetworkConnectivityObserver

class LyricsPreloadManager(
    private val context: Context,
    private val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val lyricsHelper: LyricsHelper,
) {
    fun onSongChanged(currentIndex: Int, queue: List<MediaMetadata>) {
        // Disabled to reduce background network/database work and battery drain.
    }

    fun destroy() {
        // No-op because preload is disabled.
    }
}
