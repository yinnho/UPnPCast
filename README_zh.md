# UPnPCast

[![CI/CD](https://github.com/yinnho/UPnPCast/actions/workflows/ci.yml/badge.svg)](https://github.com/yinnho/UPnPCast/actions)
[![Release](https://img.shields.io/github/v/release/yinnho/UPnPCast)](https://github.com/yinnho/UPnPCast/releases)
[![License](https://img.shields.io/github/license/yinnho/UPnPCast)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/yinnho.com/upnpcast)](https://central.sonatype.com/artifact/yinnho.com/upnpcast)
[![Weekly Downloads](https://jitpack.io/v/yinnho/UPnPCast/week.svg)](https://jitpack.io/#yinnho/UPnPCast)
[![Monthly Downloads](https://jitpack.io/v/yinnho/UPnPCast/month.svg)](https://jitpack.io/#yinnho/UPnPCast)

🚀 现代化、简洁的Android DLNA/UPnP投屏库，专为替代已停止维护的Cling项目而设计。

> **中文文档** | **[English Documentation](README.md)**

## 功能特性

- 🔍 **设备发现**: 基于SSDP协议的自动DLNA/UPnP设备发现
- 📺 **媒体投屏**: 支持图片、视频、音频投屏到DLNA兼容设备
- 🎮 **播放控制**: 播放、暂停、停止、拖拽、音量控制、静音等功能
- 📱 **简单集成**: 简洁的API接口和直观的回调机制
- 🚀 **现代架构**: 使用Kotlin、协程和Android最佳实践构建
- 🔧 **高度兼容**: 经过主流电视品牌测试（小米、三星、LG、索尼）
- ⚡ **轻量级**: 最小依赖，性能优化

## 快速开始

### 安装

#### 方式一：JitPack（推荐 - 立即可用！）

在根目录的 `build.gradle` 中添加：
```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

添加依赖：
```gradle
dependencies {
    implementation 'com.github.yinnho:UPnPCast:1.1.2'
}
```

#### 方式二：Maven Central（即将推出）
```gradle
dependencies {
    implementation 'yinnho.com:upnpcast:1.1.2'
}
```

### 基本用法

```kotlin
import com.yinnho.upnpcast.DLNACast

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化
        DLNACast.init(this)
        
        // 搜索设备 - 实时累积更新
        searchDevices()
        
        // 或使用智能投屏，自动选择设备
        performSmartCast()
    }
    
    private fun searchDevices() {
        // 实时设备发现，返回累积的设备列表
        DLNACast.search(timeout = 5000) { devices ->
            // 每次发现新设备时调用，返回累积的全部设备
            updateDeviceList(devices) // 直接替换列表即可
            Log.d("DLNA", "发现 ${devices.size} 个设备")
        }
    }
    
    private fun performSmartCast() {
        // 智能投屏 - 自动查找并选择最佳设备
        DLNACast.cast("http://your-video.mp4", "视频标题") { result ->
            if (result.success) {
                Log.d("DLNA", "智能投屏开始!")
            } else {
                Log.e("DLNA", "投屏失败: ${result.message}")
            }
        }
    }
    
    // 控制播放
    private fun controlPlayback() {
        DLNACast.control(DLNACast.MediaAction.PAUSE) { success ->
            Log.d("DLNA", "暂停: $success")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        DLNACast.release()
    }
}
```

## API参考

### 核心方法

```kotlin
// 初始化库
DLNACast.init(context: Context)

// 搜索设备 - 实时累积更新
DLNACast.search(timeout: Long = 5000, callback: (devices: List<Device>) -> Unit)

// 自动投屏到可用设备
DLNACast.cast(url: String, title: String? = null, callback: (success: Boolean) -> Unit = {})

// 智能投屏，支持设备选择
// 已移除：使用 DLNACast.cast() 进行自动设备选择

// 投屏到指定设备
DLNACast.castToDevice(device: Device, url: String, title: String? = null, callback: (success: Boolean) -> Unit = {})

// 控制媒体播放
DLNACast.control(action: MediaAction, value: Any? = null, callback: (success: Boolean) -> Unit = {})

// 获取当前状态
DLNACast.getState(): State

// 释放资源
DLNACast.release()
```

### 数据类型

```kotlin
data class Device(
    val id: String,           // 设备ID
    val name: String,         // 设备名称
    val address: String,      // IP地址
    val isTV: Boolean         // 是否为电视
)

enum class MediaAction {
    PLAY, PAUSE, STOP, VOLUME, MUTE, SEEK, GET_STATE
}

enum class PlaybackState {
    IDLE, PLAYING, PAUSED, STOPPED, BUFFERING, ERROR
}

data class State(
    val isConnected: Boolean,      // 是否已连接
    val currentDevice: Device?,    // 当前设备
    val playbackState: PlaybackState, // 播放状态
    val volume: Int = -1,          // 音量
    val isMuted: Boolean = false   // 是否静音
)
```

## 文档

- 🎯 **[演示应用](app-demo/)** - 完整的示例程序，包含所有API演示
- 📖 **[API参考](#api参考)** - 上方的完整API文档
- 📋 **[更新日志](CHANGELOG.md)** - 版本历史和更新
- 🤔 **[常见问题](docs/FAQ.md)** - 常见问题解答和故障排除
- 🎯 **[最佳实践](docs/BEST_PRACTICES.md)** - 异步回调、设备管理和优化指南

## 设备兼容性

- ✅ 小米电视 (原生DLNA + 小米投屏)
- ✅ 三星智能电视
- ✅ LG智能电视  
- ✅ 索尼Bravia电视
- ✅ Android TV盒子
- ✅ Windows Media Player

## 许可证

本项目采用MIT许可证 - 详见 [LICENSE](LICENSE) 文件。

## 贡献

欢迎贡献！请查看我们的[贡献指南](CONTRIBUTING.md)了解开发指导原则和如何开始。

## 支持

- 📖 在[演示应用](app-demo/)中查看详细的使用示例
- 🐛 在[GitHub Issues](https://github.com/yinnho/UPnPCast/issues)报告问题
- 💡 欢迎功能请求！