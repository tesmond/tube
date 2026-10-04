package com.tube.tv.domain

interface ContentRepository {
    suspend fun trending(): ResultPage<BrowseItem>
    suspend fun search(query: String, page: PageToken?): ResultPage<BrowseItem>
    suspend fun channelVideos(channelUrl: String, page: PageToken?): ResultPage<BrowseItem>
    suspend fun playlistVideos(playlistUrl: String, page: PageToken?): ResultPage<BrowseItem>
}

/** The only component that knows how to turn a video id into playable streams. */
interface StreamResolver {
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): ResolvedMedia

    /** Drop any cached resolution (called when playback is released). */
    fun invalidate()
}
