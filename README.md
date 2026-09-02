# UPnPCast

[![CI/CD](https://github.com/yinnho/UPnPCast/actions/workflows/ci.yml/badge.svg)](https://github.com/yinnho/UPnPCast/actions)
[![Release](https://img.shields.io/github/v/release/yinnho/UPnPCast)](https://github.com/yinnho/UPnPCast/releases)
[![JitPack](https://jitpack.io/v/yinnho/UPnPCast.svg)](https://jitpack.io/#yinnho/UPnPCast)
[![License](https://img.shields.io/github/license/yinnho/UPnPCast)](LICENSE)

A modern Android DLNA/UPnP casting library with a coroutine-first Kotlin API — SSDP device discovery, playback control, local-file streaming and external subtitles. A clean, actively maintained replacement for the discontinued [Cling](https://github.com/4thline/cling) project.

> **[中文文档](README_zh.md)** | English Documentation

## Features

- 🔍 **Device discovery** — SSDP M-SEARCH with a dedicated listener thread, `MulticastLock` handling, and an ephemeral-port fallback when port 1900 is taken
- 📺 **Media casting** — cast remote video/audio/image URLs to any DLNA-compatible renderer with DIDL-Lite metadata
- 📱 **Local file casting** — built-in HTTP file server with Range/seek support and correct per-extension MIME types
- 🎮 **Full playback control** — play, pause, stop, seek, volume and mute via AVTransport/RenderingControl SOAP
- 📊 **Live state & progress** — `getPlaybackState()` reflects remote pause/stop; progress queries use short caching with interpolation while playing, or force refresh
- 💬 **Subtitles & metadata** — attach external subtitles (incl. Samsung `sec:SubtitleUri`), override MIME type/UPnP class, or supply full DIDL-Lite via `CastOptions`
- 🚀 **Coroutine-first API** — all network operations are `suspend` functions; no callback soup
- ✅ **Tested** — 92 unit + protocol-level integration tests driven by an in-process fake DLNA renderer
- 🪶 **Lightweight** — no OkHttp, no Gson; local serving via NanoHTTPD only

## Requirements

- Android 7.0+ (API 24)
- Casting device and TV on the same local network (DLNA is LAN-only)
- The library manifest already declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`; they merge into your app automatically. Cleartext HTTP is enabled for media URLs.

## Installation

Releases are distributed via **JitPack**:

```groovy
// settings.gradle (Groovy DSL) or settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

```groovy
// app/build.gradle
dependencies {
    implementation 'com.github.yinnho:UPnPCast:v1.3.0'
}
```

Kotlin DSL: `implementation("com.github.yinnho:UPnPCast:v1.3.0")`

## Quick Start

```kotlin
import com.yinnho.upnpcast.DLNACast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DLNACast.init(this)

        lifecycleScope.launch { castToTv() }
    }

    private suspend fun castToTv() {
        try {
            val devices = DLNACast.search(timeout = 5000)
            val tv = devices.firstOrNull { it.isTV } ?: return

            val success = DLNACast.castToDevice(
                device = tv,
                url = "http://example.com/video.mp4",
                title = "My Video"
            )
            if (success) {
                DLNACast.pause()
                DLNACast.seek(30_000)

                val state = DLNACast.getState()
                Log.d("DLNA", "Connected to ${state.currentDevice?.name}, playing: ${state.isPlaying}")
            }
        } catch (e: Exception) {
            Log.e("DLNA", "Cast failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DLNACast.cleanup()
    }
}
```

## API Reference

All network operations are `suspend` functions — call them from a coroutine.

### Lifecycle

```kotlin
fun DLNACast.init(context: Context)   // create the engine (safe to call again; replaces the old one)
fun DLNACast.cleanup()                // release everything (call in onDestroy)
fun DLNACast.getState(): State        // last observed casting state (synchronous)
fun DLNACast.clearProgressCache()     // drop cached progress (e.g. after switching media)
```

### Discovery & casting

```kotlin
suspend fun search(timeout: Long = 5000): List<Device>

suspend fun cast(url: String, title: String? = null, options: CastOptions = CastOptions()): Boolean
suspend fun castToDevice(device: Device, url: String, title: String? = null,
                         options: CastOptions = CastOptions()): Boolean

suspend fun scanLocalVideos(context: Context): List<LocalVideo>
suspend fun castLocalFile(filePath: String, device: Device, title: String? = null,
                          options: CastOptions = CastOptions())
```

`castLocalFile` starts the built-in file server and throws typed errors on failure:
`UPnPException.FileError` (unreadable file), `NetworkError` (server/URL failure), `DeviceError` (device rejected the cast). It throws `UnknownError` if the library is not initialized.

### Playback control

```kotlin
suspend fun control(action: MediaAction, value: Any? = null): Boolean

// Convenience wrappers
suspend fun play(): Boolean
suspend fun pause(): Boolean
suspend fun stop(): Boolean
suspend fun seek(positionMs: Long): Boolean
suspend fun setVolume(volume: Int): Boolean   // 0-100
suspend fun setMute(mute: Boolean): Boolean
```

### State, progress & volume queries

```kotlin
suspend fun getPlaybackState(): PlaybackState   // live query (GetTransportInfo) — reflects remote pause/stop
suspend fun getProgress(): Pair<Long, Long>?    // (currentMs, totalMs), short cache + interpolation
suspend fun getProgressRealtime(): Pair<Long, Long>?  // force refresh, no cache
suspend fun getVolume(): Pair<Int?, Boolean?>?  // (volume 0-100, isMuted)
suspend fun refreshProgressCache(): Boolean
suspend fun refreshVolumeCache(): Boolean
```

### Data types

```kotlin
data class Device(
    val id: String,        // unique device identifier (UDN)
    val name: String,      // friendly name, e.g. "Living Room TV"
    val address: String,   // IP address
    val isTV: Boolean      // renderer-like device
)

data class LocalVideo(
    val id: String,
    val title: String,
    val path: String,      // absolute file path
    val duration: String,  // formatted, e.g. "01:23:45"
    val size: String,      // formatted, e.g. "1.2 GB"
    val durationMs: Long
)

enum class MediaAction { PLAY, PAUSE, STOP, VOLUME, MUTE, SEEK }

enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED, BUFFERING, ERROR }

data class State(
    val isConnected: Boolean,
    val currentDevice: Device?,
    val playbackState: PlaybackState,
    val volume: Int = -1,       // -1 = unknown
    val isMuted: Boolean = false
) {
    val isPlaying: Boolean  // playbackState == PLAYING
    val isPaused: Boolean   // playbackState == PAUSED
    val isIdle: Boolean     // playbackState == IDLE
}
```

### CastOptions

Customize what is sent to the renderer:

```kotlin
// Attach an external subtitle (the URL must be reachable from the TV)
val options = CastOptions(subtitleUri = "http://192.168.1.100:8080/movie.srt")
DLNACast.castToDevice(device, url, title, options)

// Full control: send your own DIDL-Lite metadata verbatim
val custom = CastOptions(metadata = """<DIDL-Lite ...>...</DIDL-Lite>""")
```

| Field | Description |
|---|---|
| `metadata` | Full DIDL-Lite override, sent verbatim (other fields ignored) |
| `subtitleUri` | HTTP(S) subtitle URL attached to the cast |
| `subtitleMimeType` | Subtitle MIME type, defaults to `text/srt` |
| `mimeType` | Overrides the auto-detected media MIME type |
| `upnpClass` | Overrides the UPnP object class |

## Advanced Usage

### Local file casting

```kotlin
lifecycleScope.launch {
    val videos = DLNACast.scanLocalVideos(this@MainActivity)
    val video = videos.firstOrNull() ?: return@launch
    val tv = DLNACast.search().firstOrNull { it.isTV } ?: return@launch

    try {
        DLNACast.castLocalFile(video.path, tv, video.title)
    } catch (e: UPnPException.FileError) {
        Log.e("DLNA", "Cannot read ${video.path}")
    } catch (e: UPnPException.NetworkError) {
        Log.e("DLNA", "File server failed to start")
    } catch (e: UPnPException.DeviceError) {
        Log.e("DLNA", "Device rejected the cast")
    }
}
```

### Progress monitoring

```kotlin
lifecycleScope.launch {
    while (isActive) {
        DLNACast.getProgress()?.let { (currentMs, totalMs) ->
            progressBar.progress = if (totalMs > 0) (currentMs * 100 / totalMs).toInt() else 0
        }
        delay(1000)
    }
}
```

Use `getProgressRealtime()` when you need an immediate fresh value (e.g. right after a seek). After switching media on the same device, call `DLNACast.clearProgressCache()` to drop stale positions.

### Reacting to remote state changes

```kotlin
// The user paused playback with the TV remote
lifecycleScope.launch {
    val state = DLNACast.getPlaybackState()
    playButton.isVisible = state == DLNACast.PlaybackState.PAUSED
}
```

## Device Compatibility

- ✅ Xiaomi TV (native DLNA + Mi Cast)
- ✅ Samsung Smart TV
- ✅ LG Smart TV
- ✅ Sony Bravia TV
- ✅ Android TV boxes
- ✅ Windows Media Player

## Documentation

- 🎯 **[Demo app](app-demo/)** — working example application
- 🤔 **[FAQ](docs/FAQ.md)** — common problems and troubleshooting
- 📋 **[Changelog](CHANGELOG.md)** — version history
- 🗺️ **[Roadmap](ROADMAP.md)** — what's planned next
- 🎯 **[Best practices](docs/BEST_PRACTICES.md)** — coroutine patterns and error handling

## Contributing

Contributions are welcome! See the [Contributing Guide](CONTRIBUTING.md). For security issues, see [SECURITY.md](SECURITY.md).

## License

MIT License — see [LICENSE](LICENSE).
