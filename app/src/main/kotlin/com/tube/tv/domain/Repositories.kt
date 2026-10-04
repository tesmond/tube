package com.tube.tv.domain

interface ContentRepository {
    suspend fun trending(): ResultPage<BrowseItem>
    suspend fun search(query: String, page: PageToken?): ResultPage<BrowseItem>
    suspend fun channelVideos(channelUrl: String, page: PageToken?): ResultPage<BrowseItem>
    suspend fun playlistVideos(playlistUrl: String, page: PageToken?): ResultPage<BrowseItem>
}

/** Account-backed feeds. Home falls back to public sources when the personalised feed is unavailable. */
interface FeedRepository {
    suspend fun home(page: PageToken?): ResultPage<BrowseItem>

    /** Requires sign-in; throws [ContentException] with [ErrorKind.SIGNED_OUT] otherwise. */
    suspend fun history(page: PageToken?): ResultPage<BrowseItem>
}

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

sealed interface LoginResult {
    data object Pending : LoginResult
    data object SlowDown : LoginResult
    data object Success : LoginResult
    data class Failed(val reason: String) : LoginResult
}

/** TV-style sign-in: show a code, the user approves it on their phone/computer. */
interface AuthRepository {
    val signedIn: kotlinx.coroutines.flow.StateFlow<Boolean>
    suspend fun startDeviceLogin(): DeviceCode
    suspend fun pollLogin(code: DeviceCode): LoginResult
    fun signOut()
}

/** The only component that knows how to turn a video id into playable streams. */
interface StreamResolver {
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): ResolvedMedia

    /** Drop any cached resolution (called when playback is released). */
    fun invalidate()
}
