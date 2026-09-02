# 最佳实践

本文覆盖 UPnPCast 在真实应用中的推荐用法：生命周期、轮询、状态管理、错误处理与设备选择。所有示例基于 v1.3.0 的协程 API。

## 目录

- [生命周期管理](#生命周期管理)
- [设备发现与选择](#设备发现与选择)
- [进度轮询](#进度轮询)
- [状态同步](#状态同步)
- [错误处理](#错误处理)
- [性能与稳定性](#性能与稳定性)

## 生命周期管理

### init/cleanup 与宿主对齐

`DLNACast.init()` 创建内部引擎（协程作用域、SSDP 发现、控制器与缓存），`cleanup()` 全部释放。二者应与承载 UI 的生命周期对齐：

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DLNACast.init(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        DLNACast.cleanup()
    }
}
```

要点：

- `init()` 可安全重复调用——旧引擎会被先释放再重建，`cleanup()` → `init()` 循环不会泄漏
- 传 `Activity` 即可，库内部只保留 `applicationContext`，不会持有 Activity 引用
- 未初始化时查询类 API 返回中性默认值（`false` / `null` / `IDLE` / 空列表），`castLocalFile` 抛 `UPnPException.UnknownError`——无需自行判断初始化状态

### 投屏会话用独立协程作用域

不要把投屏控制塞进随 UI 销毁的作用域。用一个与"投屏会话"同生命周期的 `Job` 管理轮询：

```kotlin
private var castSession: Job? = null

private fun startCastMonitor() {
    castSession?.cancel()
    castSession = lifecycleScope.launch {
        while (isActive) {
            refreshUi()
            delay(1000)
        }
    }
}

override fun onDestroy() {
    castSession?.cancel()
    DLNACast.cleanup()
    super.onDestroy()
}
```

## 设备发现与选择

### 按特征挑选设备

```kotlin
suspend fun findTv(timeout: Long = 5000): DLNACast.Device? {
    val devices = DLNACast.search(timeout)
    return devices.firstOrNull { it.isTV }
        ?: devices.maxByOrNull { it.name.length } // 兜底策略按业务定
}
```

### 记住上次的设备

用 `Device.id`（UDN，设备唯一且稳定）持久化用户选择，下次直连：

```kotlin
// 保存
prefs.edit().putString("last_tv_id", device.id).apply()

// 复用
val lastId = prefs.getString("last_tv_id", null)
val device = DLNACast.search().firstOrNull { it.id == lastId }
    ?: DLNACast.search().firstOrNull { it.isTV } // 上次的设备不在线，回退
```

### 投屏失败自动重试

```kotlin
suspend fun castWithRetry(url: String, title: String, maxRetries: Int = 3): Boolean {
    repeat(maxRetries) { attempt ->
        if (DLNACast.cast(url, title)) return true
        delay(2000L * (attempt + 1)) // 线性退避
    }
    return false
}
```

## 进度轮询

### 用 getProgress() 轮询 UI

`getProgress()` 带短缓存与播放中插值，专为每秒级轮询设计，不会每秒都打网络请求：

```kotlin
lifecycleScope.launch {
    while (isActive) {
        DLNACast.getProgress()?.let { (currentMs, totalMs) ->
            val percent = if (totalMs > 0) (currentMs * 100 / totalMs).toInt() else 0
            seekBar.progress = percent
            timeText.text = formatTime(currentMs)
        }
        delay(1000)
    }
}
```

### Seek 后强制刷新

seek 之后缓存里的旧位置可能还会被读一次，立即用实时接口修正：

```kotlin
seekBar.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_UP) {
        val targetMs = seekBar.progress.toLong() * 1000
        lifecycleScope.launch {
            DLNACast.seek(targetMs)
            DLNACast.getProgressRealtime() // 立刻拉取新位置
        }
    }
    false
}
```

### 切换媒体后清缓存

同一设备连续投屏不同视频时，旧进度缓存会污染新会话：

```kotlin
DLNACast.clearProgressCache()
DLNACast.castToDevice(device, newUrl, newTitle)
```

## 状态同步

### 区分"本地操作"与"远端操作"

- 用户在你的 App 内暂停 → 调用 `pause()` 后本地即知结果
- 用户用**电视遥控器**暂停 → 需要主动查询：

```kotlin
suspend fun syncRemoteState(): DLNACast.PlaybackState {
    val live = DLNACast.getPlaybackState() // GetTransportInfo 实时查询
    playPauseButton.isChecked = live == DLNACast.PlaybackState.PLAYING
    return live
}
```

推荐把它放进上一节的轮询循环里，每 1-2 秒对齐一次 UI 与设备真实状态。

### 处理 STOPPED

设备播放到结尾、或被遥控器停止时状态为 `STOPPED`。在此状态下释放 UI 或提示"播放结束"：

```kotlin
when (DLNACast.getPlaybackState()) {
    DLNACast.PlaybackState.STOPPED -> showPlayNextSuggestion()
    else -> {}
}
```

## 错误处理

### 网络投屏：布尔返回 + 异常兜底

`cast` / `castToDevice` / 控制类方法返回 `Boolean` 表示设备是否接受，网络异常以 `false` 返回而不抛出——按需 try/catch：

```kotlin
lifecycleScope.launch {
    try {
        val ok = DLNACast.castToDevice(device, url, title)
        if (!ok) showError("设备拒绝了投屏请求")
    } catch (e: CancellationException) {
        throw e // 协程取消必须继续传播
    } catch (e: Exception) {
        showError("投屏失败: ${e.message}")
    }
}
```

注意：`catch (e: Exception)` 前放行 `CancellationException`，否则会破坏协程取消语义。

### 本地投屏：类型化异常

`castLocalFile` 在失败源头抛出类型化异常，可精确分支：

```kotlin
try {
    DLNACast.castLocalFile(filePath, device, title)
} catch (e: UPnPException.FileError) {
    requestStoragePermission() // 大概率是权限/路径问题
} catch (e: UPnPException.NetworkError) {
    checkWifi()
} catch (e: UPnPException.DeviceError) {
    showDeviceRejectedDialog()
}
```

### 统一 UI 提示

```kotlin
fun Throwable.userMessage(): String = when (this) {
    is UPnPException.FileError -> "文件不可读"
    is UPnPException.NetworkError -> "网络异常"
    is UPnPException.DeviceError -> "设备拒绝了请求"
    else -> message ?: "未知错误"
}
```

## 性能与稳定性

- **轮询间隔 1 秒起步**：`getProgress()` 的缓存与插值就是为这个频率设计的；更高频率请用 `getProgressRealtime()` 并自行评估设备压力
- **不要并发大量 SOAP 请求**：多数电视的渲染服务是单线程的，并发查询会排队甚至超时
- **音量缓存**：`getVolume()` 同样走缓存，改变音量后如需立即回读可先 `refreshVolumeCache()`
- **本地投屏保持网络活跃**：电视直接从手机拉流，锁屏或 WiFi 休眠会中断传输；长视频建议配合前台服务
- **格式兼容**：优先 MP4 (H.264 + AAC)；字幕用外挂 SRT 并确保 URL 电视可达

## 反模式

| 反模式 | 问题 | 正确做法 |
|---|---|---|
| 在 `onDestroy` 之外调用 `cleanup()` 后继续投屏 | 引擎已释放，操作返回默认值 | 与 Activity 生命周期对齐 |
| 每帧调用 `getProgressRealtime()` | 高频 SOAP 请求拖垮电视服务 | `getProgress()` 轮询 + 关键时刻 realtime |
| 忽略 `CancellationException` | 协程取消语义被破坏 | catch 后先 re-throw |
| 用 `Device.name` 做设备持久化主键 | 名称可变、可重复 | 用 `Device.id`（UDN） |
| 切换视频不清理进度缓存 | UI 显示上一个视频的位置 | `clearProgressCache()` |

## 更多

- [常见问题 FAQ](FAQ.md)
- [Demo 应用](../app-demo/)
- [GitHub Issues](https://github.com/yinnho/UPnPCast/issues)
