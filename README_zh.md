# UPnPCast

[![CI/CD](https://github.com/yinnho/UPnPCast/actions/workflows/ci.yml/badge.svg)](https://github.com/yinnho/UPnPCast/actions)
[![Release](https://img.shields.io/github/v/release/yinnho/UPnPCast)](https://github.com/yinnho/UPnPCast/releases)
[![JitPack](https://jitpack.io/v/yinnho/UPnPCast.svg)](https://jitpack.io/#yinnho/UPnPCast)
[![License](https://img.shields.io/github/license/yinnho/UPnPCast)](LICENSE)

现代化 Android DLNA/UPnP 投屏库，协程优先的 Kotlin API——SSDP 设备发现、播放控制、本地文件投屏、外挂字幕。已停止维护的 [Cling](https://github.com/4thline/cling) 项目的干净替代品，持续维护中。

> 中文文档 | **[English Documentation](README.md)**

## 功能特性

- 🔍 **设备发现** — SSDP M-SEARCH + 独立监听线程、`MulticastLock` 处理、1900 端口被占用时自动回退临时端口
- 📺 **媒体投屏** — 投射远程视频/音频/图片 URL 到任意 DLNA 兼容设备，带 DIDL-Lite 元数据
- 📱 **本地文件投屏** — 内置 HTTP 文件服务器，支持 Range/拖拽与按扩展名返回正确的 MIME 类型
- 🎮 **完整播放控制** — 播放、暂停、停止、拖拽、音量、静音（AVTransport/RenderingControl SOAP）
- 📊 **实时状态与进度** — `getPlaybackState()` 反映远端暂停/停止；进度查询带短缓存与播放中插值，也可强制刷新
- 💬 **字幕与元数据** — `CastOptions` 支持外挂字幕（含三星 `sec:SubtitleUri`）、MIME/UPnP 类别覆盖、完整 DIDL-Lite 自定义
- 🚀 **协程优先 API** — 所有网络操作均为 `suspend` 函数，无回调地狱
- ✅ **有测试保障** — 92 个单元 + 协议级集成测试，由进程内假 DLNA 渲染器驱动
- 🪶 **轻量** — 不依赖 OkHttp、Gson；本地文件服务仅用 NanoHTTPD

## 环境要求

- Android 7.0+（API 24）
- 手机与电视在同一局域网（DLNA 仅限局域网）
- 库的 manifest 已声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE` 权限，会自动合并到你的应用；媒体 URL 需要 HTTP 明文传输，已默认开启

## 安装

正式版本通过 **JitPack** 分发：

```groovy
// settings.gradle（Groovy DSL）或 settings.gradle.kts
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

Kotlin DSL 写法：`implementation("com.github.yinnho:UPnPCast:v1.3.0")`

## 快速开始

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
                title = "我的视频"
            )
            if (success) {
                DLNACast.pause()
                DLNACast.seek(30_000)

                val state = DLNACast.getState()
                Log.d("DLNA", "已连接 ${state.currentDevice?.name}，播放中: ${state.isPlaying}")
            }
        } catch (e: Exception) {
            Log.e("DLNA", "投屏失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DLNACast.cleanup()
    }
}
```

## API 参考

所有网络操作均为 `suspend` 函数，请在协程中调用。

### 生命周期

```kotlin
fun DLNACast.init(context: Context)   // 创建引擎（可重复调用；会替换旧引擎）
fun DLNACast.cleanup()                // 释放全部资源（在 onDestroy 中调用）
fun DLNACast.getState(): State        // 最近一次观测到的投屏状态（同步）
fun DLNACast.clearProgressCache()     // 清除进度缓存（如切换媒体后）
```

### 发现与投屏

```kotlin
suspend fun search(timeout: Long = 5000): List<Device>

suspend fun cast(url: String, title: String? = null, options: CastOptions = CastOptions()): Boolean
suspend fun castToDevice(device: Device, url: String, title: String? = null,
                         options: CastOptions = CastOptions()): Boolean

suspend fun scanLocalVideos(context: Context): List<LocalVideo>
suspend fun castLocalFile(filePath: String, device: Device, title: String? = null,
                          options: CastOptions = CastOptions())
```

`castLocalFile` 会启动内置文件服务器，失败时抛出类型化异常：
`UPnPException.FileError`（文件不可读）、`NetworkError`（服务器/URL 失败）、`DeviceError`（设备拒绝投屏）；未初始化时抛出 `UnknownError`。

### 播放控制

```kotlin
suspend fun control(action: MediaAction, value: Any? = null): Boolean

// 便捷方法
suspend fun play(): Boolean
suspend fun pause(): Boolean
suspend fun stop(): Boolean
suspend fun seek(positionMs: Long): Boolean
suspend fun setVolume(volume: Int): Boolean   // 0-100
suspend fun setMute(mute: Boolean): Boolean
```

### 状态、进度与音量查询

```kotlin
suspend fun getPlaybackState(): PlaybackState   // 实时查询（GetTransportInfo）——反映远端暂停/停止
suspend fun getProgress(): Pair<Long, Long>?    // (当前位置ms, 总时长ms)，短缓存 + 插值
suspend fun getProgressRealtime(): Pair<Long, Long>?  // 强制刷新，不走缓存
suspend fun getVolume(): Pair<Int?, Boolean?>?  // (音量 0-100, 是否静音)
suspend fun refreshProgressCache(): Boolean
suspend fun refreshVolumeCache(): Boolean
```

### 数据类型

```kotlin
data class Device(
    val id: String,        // 唯一设备标识（UDN）
    val name: String,      // 友好名称，如"客厅电视"
    val address: String,   // IP 地址
    val isTV: Boolean      // 是否为渲染设备
)

data class LocalVideo(
    val id: String,
    val title: String,
    val path: String,      // 文件绝对路径
    val duration: String,  // 格式化时长，如 "01:23:45"
    val size: String,      // 格式化大小，如 "1.2 GB"
    val durationMs: Long
)

enum class MediaAction { PLAY, PAUSE, STOP, VOLUME, MUTE, SEEK }

enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED, BUFFERING, ERROR }

data class State(
    val isConnected: Boolean,
    val currentDevice: Device?,
    val playbackState: PlaybackState,
    val volume: Int = -1,       // -1 表示未知
    val isMuted: Boolean = false
) {
    val isPlaying: Boolean  // playbackState == PLAYING
    val isPaused: Boolean   // playbackState == PAUSED
    val isIdle: Boolean     // playbackState == IDLE
}
```

### CastOptions

自定义发送给设备的参数：

```kotlin
// 附加外挂字幕（URL 需电视可访问）
val options = CastOptions(subtitleUri = "http://192.168.1.100:8080/movie.srt")
DLNACast.castToDevice(device, url, title, options)

// 高级用法：原样发送自定义 DIDL-Lite 元数据
val custom = CastOptions(metadata = """<DIDL-Lite ...>...</DIDL-Lite>""")
```

| 字段 | 说明 |
|---|---|
| `metadata` | 完整 DIDL-Lite 覆盖，原样发送（设置后忽略其他字段） |
| `subtitleUri` | 附加到投屏的字幕 HTTP(S) 地址 |
| `subtitleMimeType` | 字幕 MIME 类型，默认 `text/srt` |
| `mimeType` | 覆盖自动识别的媒体 MIME 类型 |
| `upnpClass` | 覆盖 UPnP 对象类别 |

## 高级用法

### 本地文件投屏

```kotlin
lifecycleScope.launch {
    val videos = DLNACast.scanLocalVideos(this@MainActivity)
    val video = videos.firstOrNull() ?: return@launch
    val tv = DLNACast.search().firstOrNull { it.isTV } ?: return@launch

    try {
        DLNACast.castLocalFile(video.path, tv, video.title)
    } catch (e: UPnPException.FileError) {
        Log.e("DLNA", "无法读取 ${video.path}")
    } catch (e: UPnPException.NetworkError) {
        Log.e("DLNA", "文件服务器启动失败")
    } catch (e: UPnPException.DeviceError) {
        Log.e("DLNA", "设备拒绝了投屏请求")
    }
}
```

### 进度监控

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

需要立即获取最新值时（如拖拽后）使用 `getProgressRealtime()`。在同一设备上切换媒体后，调用 `DLNACast.clearProgressCache()` 清除旧的进度缓存。

### 响应远端状态变化

```kotlin
// 用户用电视遥控器暂停了播放
lifecycleScope.launch {
    val state = DLNACast.getPlaybackState()
    playButton.isVisible = state == DLNACast.PlaybackState.PAUSED
}
```

## 设备兼容性

- ✅ 小米电视（原生 DLNA + 小米投屏）
- ✅ 三星智能电视
- ✅ LG 智能电视
- ✅ 索尼 Bravia 电视
- ✅ Android TV 盒子
- ✅ Windows Media Player

## 文档

- 🎯 **[演示应用](app-demo/)** — 完整示例程序
- 🤔 **[常见问题](docs/FAQ.md)** — 常见问题与故障排除
- 📋 **[更新日志](CHANGELOG.md)** — 版本历史
- 🗺️ **[路线图](ROADMAP.md)** — 后续计划
- 🎯 **[最佳实践](docs/BEST_PRACTICES.md)** — 协程用法与错误处理

## 贡献

欢迎贡献！请查看[贡献指南](CONTRIBUTING.md)。安全问题请参考 [SECURITY.md](SECURITY.md)。

## 许可证

MIT License — 详见 [LICENSE](LICENSE)。
