# tube

Native YouTube client for Android TV. Kotlin + Jetpack Compose for TV + Media3/ExoPlayer.

## Build

There is no Gradle wrapper checked in yet. Either open the project in Android Studio (Ladybug or newer) or run
`gradle wrapper --gradle-version 8.11.1` once, then:

```
./gradlew :app:testDebugUnitTest      # FormatSelector / ResumeStore tests
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, Android SDK 35. minSdk is 24.

## Layout (ADR section -> code)

| ADR | Where |
| --- | --- |
| 1 Discovery, 2 Stream resolution | `domain/` (interfaces + app models), `data/` (the only code that touches NewPipeExtractor) |
| 3, 4, 5 Playback, adaptive streams, quality | `playback/PlaybackManager`, `FormatSelector` (pure, unit-tested), `MediaCodecCapabilities` |
| 6, 7 Controls, remote | `ui/player/` |
| 8 MediaSession | `PlaybackManager` owns the session, `PlaybackService` hosts the notification/foreground state |
| 9 Buffering | `PlayerFactory` (bounded bytes + durations, smaller on low-RAM devices) |
| 10 Lifecycle | One ExoPlayer, created on `play()`, fully released on `stop()` / leaving the player / 5 min in background |
| 11, 12 UI, images | `ui/`, bounded Coil caches in `AppContainer` |
| 13 Concurrency | coroutines everywhere, `runInterruptible` around blocking extractor calls, ViewModel-scoped cancellation |
| 14, 15 Failure and recovery | `PlaybackManager.recover/retry`, `ui/Messages.kt` |

## Deliberate choices / known gaps

- **Extractor**: stream resolution uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) behind the
  `StreamResolver` / `ContentRepository` interfaces, so it can be swapped without touching playback. It is GPL-3.0; see the
  licensing note below.
- **Auto quality** picks the best format the device can decode and the measured bandwidth sustains, and steps down one
  resolution after repeated stalls. Because YouTube video and audio are separate progressive files, ExoPlayer cannot adapt
  mid-stream the way it does with DASH; switching rebuilds the source at the current position.
- **Not implemented**: audio-only mode, HDR tone-mapping choices, a signed release config, a Gradle wrapper.
- **Needs on-device validation** (ADR "Compliance and Verification"): buffer sizes on low-end hardware, 4K decode, focus
  paths, long-session memory, googlevideo throttling behaviour.
