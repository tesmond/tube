package com.tube.tv.data

import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.ChannelItem
import com.tube.tv.domain.ContentRepository
import com.tube.tv.domain.PageToken
import com.tube.tv.domain.PlaylistItem
import com.tube.tv.domain.ResultPage
import com.tube.tv.domain.VideoItem
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.Page as NPPage

/** Maps extractor results to app models immediately so no extractor objects are retained. */
class NewPipeContentRepository(private val client: OkHttpClient) : ContentRepository {

    private val yt: StreamingService get() = ServiceList.YouTube

    private class ChannelCursor(val tab: ListLinkHandler, val page: NPPage)

    override suspend fun trending(): ResultPage<BrowseItem> = blockingIo(client) {
        val extractor = yt.kioskList.defaultKioskExtractor
        extractor.fetchPage()
        val page = extractor.initialPage
        ResultPage(page.items.mapNotNull { map(it) }, page.nextPage?.let { PageToken(it) })
    }

    override suspend fun search(query: String, page: PageToken?): ResultPage<BrowseItem> =
        blockingIo(client) {
            val handler = yt.searchQHFactory.fromQuery(query)
            if (page == null) {
                val info = SearchInfo.getInfo(yt, handler)
                ResultPage(info.relatedItems.mapNotNull { map(it) }, info.nextPage?.let { PageToken(it) })
            } else {
                val more = SearchInfo.getMoreItems(yt, handler, page.raw as NPPage)
                ResultPage(more.items.mapNotNull { map(it) }, more.nextPage?.let { PageToken(it) })
            }
        }

    override suspend fun channelVideos(channelUrl: String, page: PageToken?): ResultPage<BrowseItem> =
        blockingIo(client) {
            if (page == null) {
                val info = ChannelInfo.getInfo(yt, channelUrl)
                val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                    ?: info.tabs.firstOrNull()
                    ?: return@blockingIo ResultPage(emptyList(), null)
                val extractor = yt.getChannelTabExtractor(tab)
                extractor.fetchPage()
                val first = extractor.initialPage
                ResultPage(
                    first.items.mapNotNull { map(it) },
                    first.nextPage?.let { PageToken(ChannelCursor(tab, it)) },
                )
            } else {
                val cursor = page.raw as ChannelCursor
                val more = yt.getChannelTabExtractor(cursor.tab).getPage(cursor.page)
                ResultPage(
                    more.items.mapNotNull { map(it) },
                    more.nextPage?.let { PageToken(ChannelCursor(cursor.tab, it)) },
                )
            }
        }

    override suspend fun playlistVideos(playlistUrl: String, page: PageToken?): ResultPage<BrowseItem> =
        blockingIo(client) {
            if (page == null) {
                val info = PlaylistInfo.getInfo(yt, playlistUrl)
                ResultPage(info.relatedItems.mapNotNull { map(it) }, info.nextPage?.let { PageToken(it) })
            } else {
                val more = PlaylistInfo.getMoreItems(yt, playlistUrl, page.raw as NPPage)
                ResultPage(more.items.mapNotNull { map(it) }, more.nextPage?.let { PageToken(it) })
            }
        }

    private fun map(item: InfoItem): BrowseItem? = when (item) {
        is StreamInfoItem -> {
            val id = runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url) }.getOrNull()
            if (id == null) null else VideoItem(
                videoId = id,
                title = item.name.orEmpty(),
                channelName = item.uploaderName.orEmpty(),
                durationSeconds = item.duration,
                thumbnailUrl = bestThumbnail(item.thumbnails),
                published = item.textualUploadDate,
                isLive = item.duration < 0,
            )
        }
        is ChannelInfoItem -> ChannelItem(
            channelUrl = item.url,
            name = item.name.orEmpty(),
            thumbnailUrl = bestThumbnail(item.thumbnails),
            subscribers = item.subscriberCount,
        )
        is PlaylistInfoItem -> PlaylistItem(
            playlistUrl = item.url,
            name = item.name.orEmpty(),
            uploader = item.uploaderName.orEmpty(),
            videoCount = item.streamCount,
            thumbnailUrl = bestThumbnail(item.thumbnails),
        )
        else -> null
    }

    /** Smallest image that is still sharp at card size (~480px); avoids fetching huge thumbnails. */
    private fun bestThumbnail(images: List<Image>): String? {
        val usable = images.filter { it.url.isNotEmpty() }
        return (usable.filter { it.width >= 320 }.minByOrNull { it.width }
            ?: usable.maxByOrNull { it.width })?.url
    }
}
