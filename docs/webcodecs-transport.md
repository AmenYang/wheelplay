# WebSocket + WebCodecs 传输层

这个 fork 把 [DashPlay](https://github.com/yizhixiaoheigou/DashPlay)（上游是 [PlayPort](https://github.com/youcci/playport)）的浏览器传输方案搬进了 [WheelPlay](https://github.com/fython/wheelplay) 的 Android 宿主：CarPlay 的 H.264/H.265 码流直接走已有的 WebSocket，浏览器用 WebCodecs `VideoDecoder` 解码上屏。

WheelPlay 原本有两条路：WebRTC 直通（原生 libdatachannel + 自写 RTP 打包 + DTLS/SRTP）和 JPEG 图片兜底。前者要在手机上跑一整套 WebRTC 协议栈，后者一帧一张 JPEG，延迟和带宽都不理想。DashPlay 这条路把两者都绕开了。

## 数据流

```
iPhone ──iAP2/AirPlay──> WheelPlay(Android)
                          │  EncodedVideoSink.frame()  ← Annex B 访问单元
                          ▼
                    WebVideoSource（解码参数 + 关键帧恢复）
                          │
              ┌───────────┴────────────┐
              ▼                        ▼
    WebCodecsStream            RtcVideoStream
   （WebSocket 二进制）          （原生 RTP/DTLS）
              │                        │
              ▼                        ▼
       WebStreamSender 单一写线程（video > control > audio > image）
              │
              ▼
        浏览器 /stream 这条 WebSocket
              │
              ▼
   webcodecs-player.js → VideoDecoder → canvas
```

关键点：一条 WebSocket 上同时跑视频（WCP1 二进制帧）、音频（WPA1 PCM 帧）、控制 JSON。三者靠首字节 magic 区分，不需要额外端口。

## 线格式（`WebCodecsWire.kt`）

```
[magic u32 "WCP1"][type u8][streamType u8][flags u8][rsv u8][ptsMicros u64][payload]
```

| type | payload |
|---|---|
| 1 CONFIG | `[codec u8][width u16][height u16][avcC / hvcC]` |
| 2 FRAME | 一个访问单元的 Annex B NAL 单元 |

帧头 16 字节；`flags` 最低位是关键帧标记。这套布局照抄 PlayPort 的 `Wire.kt`，只加了 WheelPlay 的 magic 以便和音频帧共存。浏览器收到 Annex B 后按 4 字节长度前缀（AVCC）重排，因为 `VideoDecoder` 在带 `description` 时期望长度前缀格式——`webcodecs-player.js` 里的 `parsePacket`/解码路径与 PlayPort 的 `annexBToAvcc` 等价。

## 拥塞与恢复

没有 RTP、没有 NACK、没有 jitter buffer，控制手段只有三处：

1. **发送侧浅队列**：`WebStreamSender` 的视频队列深度 3；写线程落后就清空队列并返回 `false`，`WebCodecsStream` 立刻 `invalidate()` 并向 iPhone 请求关键帧。
2. **帧龄淘汰**：`RtcFrameQueue` 把超过 200 ms 的帧丢掉——车里迟到的画面没有价值。
3. **解码队列背压**：页面侧 `decodeQueueSize > 8` 时丢非关键帧。

TCP 已经负责重传，所以抖动被转成延迟而不是花屏；这也正是它在 Wi-Fi 稳定时延迟更低、而在丢包严重时不如 WebRTC 的原因。

## 三种模式

设置 → 显示 → 视频传输：

| 模式 | 传输 | 适用 |
|---|---|---|
| 图片（默认） | JPEG/WebP 逐帧 | 兼容兜底；不带原生库时唯一可用的一条 |
| WebSocket + WebCodecs | 一路 TCP 二进制流 | Wi-Fi 稳定，追求最低延迟 |
| WebRTC | 原生 RTP over DTLS/SRTP | Wi-Fi 抖动大、需要抗丢包；需 `-PwheelplayRtc=true` 自行编译 |

默认是图片而不是 WebCodecs：仓库默认构建不带 libdatachannel，WebRTC 会在运行时报 `native transport unavailable` 再回退，首次启动就该给一个真能用的模式。

WebCodecs 通道只属于 `/wc` 页面，它连接时带 `?video=webcodecs`，主机端见到这个参数就直接起 WebCodecs，不再看设置的默认值。主页面（`/`）仍然走 WebRTC/图片逻辑。

## 车机实测要点（Redmi Note 7 + Tesla 浏览器）

这几条都是只有实车日志才会暴露的，改代码前先读一遍。

**`description` 必须是 avcC/hvcC，不是 Annex B。** `VideoDecoderConfig.description` 要的是原始的 codec 配置记录；把给 RTP 打包用的 Annex B SPS/PPS 塞进去，`configure()` 直接抛错，画面永远出不来。`WebVideoSource.Config` 因此同时保存两份：`parameters`（Annex B，给 WebRTC）和 `codecData`（原始记录，给 WebCodecs）。

**配置解析不能因为默认是图片就跳过。** `WebVideoSource.configure` 原本在 `StreamTechnology.JPEG` 时提前返回，结果 `/wc` 在图片模式下永远拿不到 codec 配置，请求打了也没用。现在无论默认模式是什么都解析。

**触点 id 必须从 0 开始且连续。** `TouchLease` 只接受 `0 until maxContacts()`，CarPlay 源更严，只收 `0..1`。浏览器 `pointerdown` 的 `event.pointerId` 常常从 1 起跳甚至跳号，直接拿来当触点 id 会被拒，页面显示"画面还没准备好接收触摸"。要在 `wc.js` 里重新分配稠密的下标。

**分辨率要按视口协商。** 页面不发 `viewport` 消息时，CarPlay 按默认 1280×720 出图，而车机视口是 1184×920，上下就有了黑边。`wc.js` 用 `devicePixelRatio` 折算物理像素后发 `viewport`，日志里能看到 `Starting CarPlay controller at 1184x920` 才算生效。同时 `#stage` 不要居中固定尺寸 canvas，改成全屏 `object-fit: contain`，并把指针坐标按同样的缩放和偏移映射回画面坐标。

**令牌活不过页面。** token 只存在主机进程里，最多 12 小时；`/wc` 页面却可能连着好几天。重连时必须先用 remember 的设备凭据打 `/pair/resume` 换新令牌，否则主机重启后页面会拿着死令牌无限重试，只能手动刷新。

**HUD 会挡住 CarPlay 的 home 按钮。** 左下角那个状态浮窗连上后显示 10 秒自动淡出，点击不再唤回。

## 原生依赖改为可选

libdatachannel + Mbed TLS 的编译很重，WebCodecs 这条路完全不需要它，所以默认关掉：

```sh
./gradlew :mobile:assembleDebug              # 默认：无 NDK，WebRTC 模式自动降级为图片
./gradlew :mobile:assembleDebug -PwheelplayRtc=true   # 需要 NDK 28.2.13676358 + CMake 3.22.1
```

没有原生库时 `RtcNative` 的 `System.loadLibrary` 抛 `UnsatisfiedLinkError`，`RtcVideoStream` 已经捕获 `LinkageError` 并回退到图片，不会崩。

## 认证身份

CarPlay 需要配件证书，仓库不分发。本机构建放在 `.local/auth-assets/offline-mfi/{identity.pk8,certificate.p7b}`（`mobile/build.gradle.kts` 会自动把它并进 assets）。这两个文件取自 DiPlay 的发布包 `assets/offline-mfi/`，只用于个人实验，别提交、别再分发。
