package com.tube.tv.data

import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

internal object NewPipeBootstrap {
    @Volatile private var ready = false

    fun ensure(client: OkHttpClient) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(NewPipeDownloader(client), Localization("en", "GB"), ContentCountry("GB"))
            ready = true
        }
    }
}

/**
 * Runs blocking extractor work on the IO dispatcher. [runInterruptible] interrupts the thread
 * when the coroutine is cancelled so obsolete requests stop promptly, and every failure is
 * normalised to a [com.tube.tv.domain.ContentException].
 */
internal suspend fun <T> blockingIo(client: OkHttpClient, block: () -> T): T =
    try {
        runInterruptible(Dispatchers.IO) {
            NewPipeBootstrap.ensure(client)
            block()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A cancelled request (e.g. a newer search replaced it) surfaces as an interrupted-I/O
        // exception. That is not a failure: rethrow as cancellation so no error is reported.
        currentCoroutineContext().ensureActive()
        if (e is InterruptedIOException || e is InterruptedException) throw CancellationException("interrupted")
        android.util.Log.w("Tube", "extractor request failed", e)
        throw e.toContentException()
    }
