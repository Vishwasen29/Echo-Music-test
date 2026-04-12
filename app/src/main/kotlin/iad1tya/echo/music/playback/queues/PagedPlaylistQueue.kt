package iad1tya.echo.music.playback.queues

import androidx.media3.common.MediaItem
import com.echo.innertube.YouTube
import iad1tya.echo.music.extensions.toMediaItem
import iad1tya.echo.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class PagedPlaylistQueue(
    val title: String? = null,
    val playlistId: String,
    val initialItems: List<MediaItem>,
    val startIndex: Int = 0,
    val position: Long = 0L,
    val initialContinuation: String? = null,
    val totalCount: Int? = null,
    val hideVideoSongs: Boolean = false,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    private var continuation: String? = initialContinuation
    private val seenIds = initialItems.map { it.mediaId }.toMutableSet()

    override suspend fun getInitialStatus() = Queue.Status(
        title = title,
        items = initialItems,
        mediaItemIndex = startIndex,
        position = position,
        totalCount = totalCount ?: initialItems.size,
    )

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        val token = continuation ?: return emptyList()
        val page = withContext(IO) {
            YouTube.playlistContinuation(token).getOrThrow()
        }
        continuation = page.continuation
        return page.songs
            .asSequence()
            .filter { !hideVideoSongs || !it.isVideoSong }
            .filter { seenIds.add(it.id) }
            .map { it.toMediaItem() }
            .toList()
    }
}
