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
| WebSocket + WebCodecs（默认） | 一路 TCP 二进制流 | Wi-Fi 稳定，追求最低延迟 |
| WebRTC | 原生 RTP over DTLS/SRTP | Wi-Fi 抖动大、需要抗丢包 |
| 图片 | JPEG/WebP 逐帧 | 兼容兜底 |

浏览器带 `?video=webcodecs` 连接时才启用 WebCodecs 通道；主页面（`/`）仍然走原来的 WebRTC/图片逻辑，低延迟页面在 `/wc`。

## 原生依赖改为可选

libdatachannel + Mbed TLS 的编译很重，WebCodecs 这条路完全不需要它，所以默认关掉：

```sh
./gradlew :mobile:assembleDebug              # 默认：无 NDK，WebRTC 模式自动降级为图片
./gradlew :mobile:assembleDebug -PwheelplayRtc=true   # 需要 NDK 28.2.13676358 + CMake 3.22.1
```

没有原生库时 `RtcNative` 的 `System.loadLibrary` 抛 `UnsatisfiedLinkError`，`RtcVideoStream` 已经捕获 `LinkageError` 并回退到图片，不会崩。

## 认证身份

CarPlay 需要配件证书，仓库不分发。本机构建放在 `.local/auth-assets/offline-mfi/{identity.pk8,certificate.p7b}`（`mobile/build.gradle.kts` 会自动把它并进 assets）。这两个文件取自 DiPlay 的发布包 `assets/offline-mfi/`，只用于个人实验，别提交、别再分发。
