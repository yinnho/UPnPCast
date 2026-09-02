# 常见问题解答 (FAQ)

## 设备发现

### Q: 为什么搜索不到设备？

1. **手机和电视必须连接同一个 WiFi**（DLNA 仅限局域网），且路由器未开启 AP 隔离
2. **电视的 DLNA/投屏功能已开启**：
   - 小米电视：设置 → 账号与安全 → 投屏接收
   - 三星电视：设置 → 常规 → 外部设备管理 → 投屏
   - LG 电视：设置 → 网络 → Screen Share / DLNA
3. **权限已声明**（库的 manifest 会自动合并，一般无需手动添加）：
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
   <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
   ```
4. **适当延长搜索时间**：部分电视响应 SSDP 较慢
   ```kotlin
   val devices = DLNACast.search(timeout = 10000)
   ```
5. **部分路由器/AP 过滤组播**：库在 `init()` 时已自动申请 `MulticastLock`；若仍收不到，可尝试更换网络热点验证

### Q: 搜索结果里出现重复设备？

库内部已按设备 UDN 去重。若你的业务层仍需去重：

```kotlin
val unique = devices.distinctBy { it.id }
```

### Q: 设备之前搜得到，现在搜不到了？

电视休眠或 DLNA 服务重启会导致设备短暂消失。重新调用 `search()` 即可；SSDP 也会通过 NOTIFY 缓存部分设备信息。

## 投屏失败

### Q: 投屏时提示"连接失败"？

1. **确认媒体 URL 电视可以访问** — 先在电脑浏览器打开该 URL 验证；电视与手机必须在同一网段
2. **URL 必须是 HTTP/HTTPS 直链**，不能是网页播放页
3. **媒体格式**：推荐 MP4 (H.264)、MP3、JPEG；专有格式或 DRM 内容普遍不被电视支持

### Q: 投屏成功但没有画面/声音？

- 检查电视端音量与静音状态：
  ```kotlin
  val (volume, muted) = DLNACast.getVolume() ?: return
  if (muted == true) DLNACast.setMute(false)
  ```
- 用 `getPlaybackState()` 查看设备实际传输状态，部分电视会对不支持的格式直接停止：
  ```kotlin
  val state = DLNACast.getPlaybackState()
  ```

### Q: 本地文件投屏失败？

`castLocalFile` 抛出类型化异常，按类型排查：

```kotlin
try {
    DLNACast.castLocalFile(filePath, device, title)
} catch (e: UPnPException.FileError) {
    // 文件不存在或不可读：检查路径与存储权限（Android 13+ 需要 READ_MEDIA_VIDEO）
} catch (e: UPnPException.NetworkError) {
    // 本地文件服务器启动失败：检查手机网络
} catch (e: UPnPException.DeviceError) {
    // 设备拒绝了请求：确认电视支持该格式
}
```

注意：本地投屏时电视直接从手机下载文件，**手机不能锁屏断网**，建议投屏期间保持前台服务或持有 WiFi 锁。

## 网络相关

### Q: 移动网络下能用吗？

不能。DLNA 基于局域网组播（SSDP），手机与电视必须在同一 WiFi。

### Q: 支持 IPv6 吗？

当前版本以 IPv4 为主。家庭局域网内 DLNA 设备普遍仍走 IPv4，通常无影响。

### Q: HTTP 明文请求被拦截？

库 manifest 已设置 `usesCleartextTraffic="true"`，会自动合并。若你的应用配置了更严格的 `networkSecurityConfig`，请为媒体地址放行明文流量。

## API 使用

### Q: 所有方法都能在主线程调用吗？

`search()`、`cast()` 等均为 `suspend` 函数，内部已切到 IO 线程执行网络操作，可直接在 `lifecycleScope.launch` 中调用，不会阻塞主线程。

### Q: getProgress() 和 getProgressRealtime() 的区别？

- `getProgress()`：优先返回缓存（约几秒窗口），播放中会做插值，适合 UI 轮询
- `getProgressRealtime()`：强制向设备发一次 GetPositionInfo，适合 seek 后立即刷新

```kotlin
// 进度条每秒刷新
lifecycleScope.launch {
    while (isActive) {
        DLNACast.getProgress()?.let { (currentMs, totalMs) ->
            updateProgressBar(currentMs, totalMs)
        }
        delay(1000)
    }
}
```

### Q: 用户用电视遥控器暂停了，怎么感知？

`getState()` 返回的是最近一次观测到的状态；要主动查询设备实时状态用 `getPlaybackState()`：

```kotlin
lifecycleScope.launch {
    when (DLNACast.getPlaybackState()) {
        DLNACast.PlaybackState.PAUSED -> showPlayButton()
        DLNACast.PlaybackState.PLAYING -> showPauseButton()
        else -> {}
    }
}
```

### Q: 同一设备切换视频后进度不对？

切换媒体后调用 `DLNACast.clearProgressCache()` 清除旧进度缓存。

## 更多帮助

1. 参考 [Demo 应用](../app-demo/)
2. 查看 [最佳实践](BEST_PRACTICES.md)
3. 在 [GitHub Issues](https://github.com/yinnho/UPnPCast/issues) 提问
