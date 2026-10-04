package com.tube.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import com.tube.tv.ui.AppNav
import com.tube.tv.ui.TubeTheme

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private val container get() = (application as TubeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TubeTheme { AppNav(container) } }
    }

    override fun onStart() {
        super.onStart()
        container.playbackManagerOrNull?.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        container.playbackManagerOrNull?.onAppBackground()
    }
}
