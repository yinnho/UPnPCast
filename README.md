# UPnPCast

[![CI/CD](https://github.com/yinnho/UPnPCast/actions/workflows/ci.yml/badge.svg)](https://github.com/yinnho/UPnPCast/actions)
[![Release](https://img.shields.io/github/v/release/yinnho/UPnPCast)](https://github.com/yinnho/UPnPCast/releases)
[![License](https://img.shields.io/github/license/yinnho/UPnPCast)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.yinnho.upnpcast/upnpcast)](https://central.sonatype.com/artifact/com.yinnho.upnpcast/upnpcast)
[![JitPack](https://jitpack.io/v/yinnho/UPnPCast.svg)](https://jitpack.io/#yinnho/UPnPCast)

🚀 A modern, clean Android DLNA/UPnP casting library designed as a drop-in replacement for the discontinued Cling project.

> **[中文文档](README_zh.md)** | **English Documentation**

## ✨ What's New in v1.1.2

🎯 **Enhanced Volume Control & Millisecond-Level Progress Management**
- **🔊 Complete Volume Control System**: Added `getVolume()`, `setVolume()`, and `setMute()` APIs for comprehensive volume management
- **⚡ Millisecond-Level Progress Control**: Intelligent caching with 3-second cache duration and real-time interpolation
- **🚀 Smart Cache Management**: Volume cache (5-second validity) and progress cache with async refresh mechanisms
- **🎯 Real-time Progress Tracking**: `getProgressRealtime()` for force refresh without cache dependency
- **🔄 Manual Cache Control**: Exposed cache refresh and clearing methods for advanced control
- 📊 **Enhanced State Management**: Improved `getState()` with integrated volume and mute status

## Features

- 🔍 **Device Discovery**: Automatic DLNA/UPnP device discovery with SSDP protocol
- 📺 **Media Casting**: Cast photos, videos, and audio to DLNA-compatible devices
- 🎮 **Playback Controls**: Play, pause, stop, seek, volume control, and mute functionality
- 🔊 **Advanced Volume Control**: Get/set volume, mute control with intelligent caching
- ⚡ **Millisecond Precision**: Real-time progress tracking with smart interpolation
- 📱 **Easy Integration**: Simple API with intuitive callback mechanisms
- 🚀 **Modern Architecture**: Built with Kotlin, Coroutines, and Android best practices
- 🔧 **Highly Compatible**: Tested with major TV brands (Xiaomi, Samsung, LG, Sony)
- ⚡ **Lightweight**: Minimal dependencies, optimized performance

## Quick Start

### Installation

#### Option 1: Maven Central (Recommended - Official Release!)

Add to your app's `build.gradle`:
```gradle
dependencies {
    implementation 'com.yinnho.upnpcast:upnpcast:1.1.2'
}
```

#### Option 2: JitPack (Alternative)

Add to your root `build.gradle`:
```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Add dependency:
```gradle
dependencies {
    implementation 'com.github.yinnho:UPnPCast:1.1.2'
}
```

### Basic Usage

```kotlin
import com.yinnho.upnpcast.DLNACast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize
        DLNACast.init(this)
        
        // Use coroutines for all operations
        lifecycleScope.launch {
            searchDevices()
            performSmartCast()
        }
    }
    
    private suspend fun searchDevices() {
        try {
            // Device discovery with timeout
            val devices = DLNACast.search(timeout = 5000)
            Log.d("DLNA", "Found ${devices.size} devices")
            
            // Display devices
            devices.forEach { device ->
                val icon = if (device.isTV) "📺" else "📱"
                Log.d("DLNA", "$icon ${device.name} (${device.address})")
            }
        } catch (e: Exception) {
            Log.e("DLNA", "Search failed: ${e.message}")
        }
    }
    
    private suspend fun performSmartCast() {
        try {
            // Smart cast - automatically finds and selects best device
            val success = DLNACast.cast("http://your-video.mp4", "Video Title")
            if (success) {
                Log.d("DLNA", "Smart casting started!")
                controlPlayback()
            } else {
                Log.e("DLNA", "Cast failed")
            }
        } catch (e: Exception) {
            Log.e("DLNA", "Cast error: ${e.message}")
        }
    }
    
    private suspend fun controlPlayback() {
        try {
            // Control playback
            val pauseSuccess = DLNACast.control(DLNACast.MediaAction.PAUSE)
            Log.d("DLNA", "Paused: $pauseSuccess")
            
            // Get current state
            val state = DLNACast.getState()
            Log.d("DLNA", "Connected: ${state.isConnected}, Playing: ${state.isPlaying}")
            
            // Seek to 30 seconds
            val seekSuccess = DLNACast.seek(30000)
            Log.d("DLNA", "Seeked to 30 seconds: $seekSuccess")
        } catch (e: Exception) {
            Log.e("DLNA", "Control error: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        DLNACast.cleanup()
    }
}
```

## API Reference

### 🚀 Core Methods (All Suspend Functions)

```kotlin
// Initialize the library (call once in onCreate)
DLNACast.init(context: Context)

// Search for devices (returns list of discovered devices)
suspend fun DLNACast.search(timeout: Long = 5000): List<Device>

// Smart cast - automatically selects best available device
suspend fun DLNACast.cast(url: String, title: String? = null): Boolean

// Cast to specific device
suspend fun DLNACast.castToDevice(device: Device, url: String, title: String): Boolean

// Cast local video files
suspend fun DLNACast.castLocalFile(device: Device, video: LocalVideo): Boolean

// Scan for local video files
suspend fun DLNACast.scanLocalVideos(): List<LocalVideo>

// Media control operations
suspend fun DLNACast.control(action: MediaAction): Boolean

// Seek to specific position (in milliseconds)
suspend fun DLNACast.seek(positionMs: Long): Boolean
```

### 📊 State Management

```kotlin
// Get current casting state (synchronous)
fun DLNACast.getState(): State

// Get playback progress (synchronous) 
fun DLNACast.getProgress(): Progress

// Get volume information (synchronous)
fun DLNACast.getVolume(): Volume

// Clean up resources (call in onDestroy)
fun DLNACast.cleanup()
```

### 📋 Data Types

```kotlin
// Device information
data class Device(
    val id: String,           // Unique device identifier
    val name: String,         // Display name (e.g., "Living Room TV")
    val address: String,      // IP address
    val isTV: Boolean         // Whether this is a TV device
)

// Local video file information
data class LocalVideo(
    val path: String,         // Full file path
    val name: String,         // Display name
    val size: Long,           // File size in bytes
    val duration: Long        // Duration in milliseconds
)

// Media control actions
enum class MediaAction {
    PLAY, PAUSE, STOP
}

// Playback states
enum class PlaybackState {
    IDLE,                     // Not connected or no media
    PLAYING,                  // Currently playing
    PAUSED,                   // Playback paused
    STOPPED,                  // Playback stopped
    BUFFERING,                // Loading/buffering
    ERROR                     // Error state
}

// Current casting state
data class State(
    val isConnected: Boolean,     // Connected to a device
    val currentDevice: Device?,   // Current target device
    val playbackState: PlaybackState,  // Current playback state
    val isPlaying: Boolean,       // Whether media is playing
    val isPaused: Boolean,        // Whether media is paused
    val volume: Int,              // Current volume (0-100)
    val isMuted: Boolean          // Whether audio is muted
)

// Playback progress information
data class Progress(
    val currentMs: Long,          // Current position in milliseconds
    val totalMs: Long,            // Total duration in milliseconds
    val percentage: Float         // Progress as percentage (0.0-1.0)
)

// Volume information
data class Volume(
    val level: Int,               // Volume level (0-100)
    val isMuted: Boolean          // Mute status
)
```

## 🔥 Advanced Usage Examples

### Cast to Specific Device
```kotlin
lifecycleScope.launch {
    try {
        // First, search for devices
        val devices = DLNACast.search(timeout = 5000)
        
        // Find your preferred device
        val targetDevice = devices.firstOrNull { it.name.contains("Living Room") }
        
        if (targetDevice != null) {
            // Cast to specific device
            val success = DLNACast.castToDevice(
                device = targetDevice,
                url = "http://your-video.mp4",
                title = "My Movie"
            )
            
            if (success) {
                Log.d("DLNA", "Successfully cast to ${targetDevice.name}")
            }
        }
    } catch (e: Exception) {
        Log.e("DLNA", "Cast failed: ${e.message}")
    }
}
```

### Custom Casting Options (Subtitles & Metadata)

Customize how media is presented to the target device with `CastOptions` — attach a subtitle, override the MIME type, or supply the full DIDL-Lite metadata:

```kotlin
// Attach an external subtitle (URL must be reachable by the TV)
val options = CastOptions(
    subtitleUri = "http://192.168.1.100:8080/movie.srt"
)
val success = DLNACast.castToDevice(device, url, title, options)

// Full control for advanced users: provide the DIDL-Lite metadata verbatim
val custom = CastOptions(
    metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
      <item id="1" parentID="0" restricted="1">
        <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">My Title</dc:title>
      </item>
    </DIDL-Lite>"""
)
DLNACast.cast(url, title, custom)
```

| Field | Description |
|---|---|
| `metadata` | Full DIDL-Lite override, sent verbatim (other fields ignored) |
| `subtitleUri` | HTTP(S) subtitle URL attached to the cast |
| `subtitleMimeType` | Subtitle MIME type, defaults to `text/srt` |
| `mimeType` | Overrides the auto-detected media MIME type |
| `upnpClass` | Overrides the UPnP object class |

### Local File Casting
```kotlin
lifecycleScope.launch {
    try {
        // Scan for local video files
        val localVideos = DLNACast.scanLocalVideos()
        
        // Find a video to cast
        val videoToPlay = localVideos.firstOrNull { it.name.contains("movie") }
        
        if (videoToPlay != null) {
            // Get available devices
            val devices = DLNACast.search()
            val device = devices.firstOrNull()
            
            if (device != null) {
                // Cast local file
                val success = DLNACast.castLocalFile(device, videoToPlay)
                Log.d("DLNA", "Local cast success: $success")
            }
        }
    } catch (e: Exception) {
        Log.e("DLNA", "Local cast failed: ${e.message}")
    }
}
```

### Media Control & State Monitoring
```kotlin
lifecycleScope.launch {
    try {
        // Control playback
        DLNACast.control(DLNACast.MediaAction.PAUSE)
        
        // Monitor state
        val state = DLNACast.getState()
        Log.d("DLNA", "Device: ${state.currentDevice?.name}")
        Log.d("DLNA", "Playing: ${state.isPlaying}")
        Log.d("DLNA", "Volume: ${state.volume}")
        
        // Get progress
        val progress = DLNACast.getProgress()
        Log.d("DLNA", "Progress: ${progress.percentage * 100}%")
        
        // Seek to specific position (2 minutes)
        DLNACast.seek(120000)
        
    } catch (e: Exception) {
        Log.e("DLNA", "Control failed: ${e.message}")
    }
}
```

## Documentation

- 🎯 **[Demo App](app-demo/)** - Working example application with complete API demonstration
- 📖 **[API Reference](#api-reference)** - Complete API documentation above
- 📋 **[Changelog](CHANGELOG.md)** - Version history and updates
- 🤔 **[FAQ](docs/FAQ.md)** - Frequently asked questions and troubleshooting
- 🎯 **[Best Practices](docs/BEST_PRACTICES.md)** - Async callbacks, device management, and optimization guides

## Device Compatibility

- ✅ Xiaomi TV (Native DLNA + Mi Cast)
- ✅ Samsung Smart TV
- ✅ LG Smart TV  
- ✅ Sony Bravia TV
- ✅ Android TV boxes
- ✅ Windows Media Player

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for development guidelines and how to get started.

## Support

- 📖 Detailed usage examples in the [demo app](app-demo/) 
- 🐛 Report issues on [GitHub Issues](https://github.com/yinnho/UPnPCast/issues)
- 💡 Feature requests are welcome!

// Cast media to specific device
DLNACast.castToDevice(device, "http://example.com/video.mp4", "My Video") { success ->
    // Handle result
}

// Cast local files
DLNACast.castLocalFile("/storage/emulated/0/video.mp4", "Local Video") { success, message ->
    if (success) {
        println("Local file cast successful")
    } else {
        println("Cast failed: $message")
    }
}

// Get local file URL for manual use
val fileUrl = DLNACast.getLocalFileUrl("/storage/emulated/0/video.mp4")
if (fileUrl != null) {
    DLNACast.cast(fileUrl, "My Local Video") { success ->
        // Handle result  
    }
}

// Control media playback