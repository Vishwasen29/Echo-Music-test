package iad1tya.echo.music.playback.queues

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import iad1tya.echo.music.models.MediaMetadata as EchoMediaMetadata
import iad1tya.echo.music.utils.SaavnAudioResolver

class SaavnQueue(
    private val song: SaavnAudioResolver.SaavnSearchResult,
) : Queue {
    override val preloadItem: EchoMediaMetadata? = null

    override suspend fun getInitialStatus(): Queue.Status {
        val mediaId = "saavn:${song.sourceSongId}"
        val artistText = song.artists.joinToString(", ").ifBlank { "JioSaavn" }
        val queueMetadata = EchoMediaMetadata(
            id = mediaId,
            title = song.title,
            artists = song.artists.map { EchoMediaMetadata.Artist(id = null, name = it) },
            duration = song.duration ?: -1,
            thumbnailUrl = song.thumbnailUrl,
            album = song.albumName?.let { EchoMediaMetadata.Album(id = mediaId, title = it) },
        )
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri("echo://saavn/${song.sourceSongId}".toUri())
            .setCustomCacheKey(mediaId)
            .setTag(queueMetadata)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(artistText)
                    .setSubtitle(artistText)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(song.thumbnailUrl?.toUri())
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
