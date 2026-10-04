package com.tube.tv

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory

@OptIn(UnstableApi::class)
class TubeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
