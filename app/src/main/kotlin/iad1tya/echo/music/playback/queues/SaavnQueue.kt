package iad1tya.echo.music.playback.queues

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import iad1tya.echo.music.utils.SaavnAudioResolver

class SaavnQueue(
    private val song: SaavnAudioResolver.SaavnSearchResult,
) : Queue {
    override val preloadItem: iad1tya.echo.music.models.MediaMetadata? = null

    override suspend fun getInitialStatus(): Queue.Status {
        val mediaId = "saavn:${song.sourceSongId}"
        val artistText = song.artists.joinToString(", ").ifBlank { "JioSaavn" }
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri("echo://saavn/${song.sourceSongId}".toUri())
            .setCustomCacheKey(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(artistText)
                    .setAlbumTitle(song.albumName)
                    .build()
            )
            .build()

        return Queue.Status(
            title = "JioSaavn",
            items = listOf(item),
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage(): List<MediaItem> = emptyList()
}
