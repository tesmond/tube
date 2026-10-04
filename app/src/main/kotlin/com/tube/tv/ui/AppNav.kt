package com.tube.tv.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tube.tv.AppContainer
import com.tube.tv.domain.BrowseItem
import com.tube.tv.domain.ChannelItem
import com.tube.tv.domain.PlaylistItem
import com.tube.tv.domain.VideoItem
import com.tube.tv.ui.browse.ChannelScreen
import com.tube.tv.ui.browse.HomeScreen
import com.tube.tv.ui.browse.PlaylistScreen
import com.tube.tv.ui.browse.SearchScreen
import com.tube.tv.ui.player.PlayerScreen

@OptIn(UnstableApi::class)
@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()

    val open = remember(nav, container) {
        { item: BrowseItem, queue: List<String> ->
            when (item) {
                is VideoItem -> {
                    container.playbackManager.setQueue(queue, item.videoId)
                    nav.navigate("player/${item.videoId}")
                }
                is ChannelItem ->
                    nav.navigate("channel?url=${Uri.encode(item.channelUrl)}&title=${Uri.encode(item.name)}")
                is PlaylistItem ->
                    nav.navigate("playlist?url=${Uri.encode(item.playlistUrl)}&title=${Uri.encode(item.name)}")
            }
        }
    }

    val urlTitleArgs = listOf(
        navArgument("url") { type = NavType.StringType; nullable = true },
        navArgument("title") { type = NavType.StringType; defaultValue = "" },
    )

    // Minimal animation during navigation (ADR section 11): no transitions at all.
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("home") {
            HomeScreen(container, onSearch = { nav.navigate("search") }, onOpen = open)
        }
        composable("search") { SearchScreen(container, open) }
        composable("channel?url={url}&title={title}", urlTitleArgs) { entry ->
            ChannelScreen(
                container,
                entry.arguments?.getString("url").orEmpty(),
                entry.arguments?.getString("title").orEmpty(),
                open,
            )
        }
        composable("playlist?url={url}&title={title}", urlTitleArgs) { entry ->
            PlaylistScreen(
                container,
                entry.arguments?.getString("url").orEmpty(),
                entry.arguments?.getString("title").orEmpty(),
                open,
            )
        }
        composable(
            "player/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                manager = container.playbackManager,
                videoId = entry.arguments?.getString("id").orEmpty(),
                onExit = { nav.popBackStack() },
            )
        }
    }
}
