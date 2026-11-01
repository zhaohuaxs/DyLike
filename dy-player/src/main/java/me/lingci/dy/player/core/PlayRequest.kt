package me.lingci.dy.player.core

/** Stable identity of one media item, independent from its current adapter position or resolved URL. */
typealias StableMediaId = String

/** Identity assigned to every attempt to load and play a media item. */
data class PlayRequest(
    val mediaId: StableMediaId,
    val requestId: Long,
    val submittedAt: Long
)

/**
 * Main-thread request gate shared by URL resolution and other asynchronous playback work.
 * Repeating the same media still creates a new request, so retries cannot accept older callbacks.
 */
class PlayRequestGate {
    private var sequence = 0L

    var current: PlayRequest? = null
        private set

    fun begin(mediaId: StableMediaId, submittedAt: Long = System.currentTimeMillis()): PlayRequest {
        return PlayRequest(mediaId, ++sequence, submittedAt).also { current = it }
    }

    fun isCurrent(request: PlayRequest): Boolean = current == request

    fun invalidate() {
        current = null
    }
}
