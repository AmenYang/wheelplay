/**
 * WebCodecs video player for WheelPlay's "WCP1" transport.
 *
 * Ported from PlayPort's `web/src/video.ts` (the decoder DashPlay ships to car browsers) and
 * adapted to the framing produced by `WebCodecsWire.kt`. Everything here runs on the browser main
 * thread: one `VideoDecoder`, one canvas, one paint per frame.
 */

const MAGIC = 0x31504357; // "WCP1"
const HEADER_BYTES = 16;
const TYPE_CONFIG = 1;
const TYPE_FRAME = 2;
const CODEC_H264 = 1;
const CODEC_H265 = 2;

const STALL_TIMEOUT_MS = 2000;
const MAX_DECODE_QUEUE = 8;

/** True when a binary WebSocket message is ours rather than a PCM audio packet. */
export function isWebCodecsPacket(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES) return false;
  return new DataView(buffer).getUint32(0, true) === MAGIC;
}

export function parsePacket(buffer) {
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  const type = bytes[4];
  const payload = bytes.subarray(HEADER_BYTES);
  if (type === TYPE_CONFIG) {
    if (payload.length < 5) return null;
    return {
      type: 'config',
      codec: payload[0],
      // The whole packet is little-endian, matching `WebCodecsWire.kt`.
      width: payload[1] | (payload[2] << 8),
      height: payload[3] | (payload[4] << 8),
      description: payload.slice(5),
    };
  }
  if (type === TYPE_FRAME) {
    return {
      type: 'frame',
      keyframe: (bytes[6] & 1) === 1,
      pts: Number(view.getBigUint64(8, true)),
      data: payload.slice(),
    };
  }
  return null;
}

export function createWebCodecsPlayer({
  canvas,
  onFrame = () => {},
  onError = () => {},
  onNeedKeyframe = () => {},
  onResize = () => {},
}) {
  let decoder = null;
  let config = null;
  let generation = 0;
  let preferHardware = true;
  let framesRendered = 0;
  let framesDecoded = 0;
  let watchdog = null;
  let lastSize = { width: 0, height: 0 };
  let lastArrival = 0;
  let dropped = 0;
  let usesDescriptionPerDecoder = false;

  const supported = () => typeof VideoDecoder !== 'undefined';

  function createDecoder() {
    return new VideoDecoder({
      output: frame => {
        try {
          paint(frame);
        } finally {
          frame.close();
        }
      },
      error: error => fail(`视频解码失败：${error.message}`),
    });
  }

  function adopt(next, message) {
    decoder = next;
    // With a description the decoder expects length-prefixed (AVCC) samples, so access units
    // arriving as Annex B have to be rewritten; without one they are passed through untouched.
    const usesDescription = message.codec === CODEC_H264
      ? message.description.length > 4
      : message.description.length > 0;
    usesDescriptionPerDecoder = usesDescription;
    if (message.width > 0 && message.height > 0) resize(message.width, message.height);
    framesRendered = 0;
    armWatchdog();
  }

  async function configure(message) {
    const token = ++generation;
    closeDecoder();
    config = message;
    if (!supported()) {
      onError('这个浏览器不支持 WebCodecs，请用 Chrome / Edge 最新版或改用设置里的 WebRTC 模式。');
      return;
    }
    try {
      if (message.codec === CODEC_H264) {
        configureH264(message, token);
      } else if (message.codec === CODEC_H265) {
        await configureH265(message, token);
      } else {
        onError(`未知的视频编码：${message.codec}`);
        onNeedKeyframe();
      }
    } catch (error) {
      if (token === generation) fail(`解码器拒绝了这路视频：${error.message || error}`);
    }
  }

  function configureH264(message, token) {
    const hasDescription = message.description && message.description.length > 4;
    const decoderConfig = {
      codec: avcCodecString(message.description),
      hardwareAcceleration: preferHardware ? 'prefer-hardware' : 'no-preference',
      optimizeForLatency: true,
      ...(message.width > 0 ? { codedWidth: message.width } : {}),
      ...(message.height > 0 ? { codedHeight: message.height } : {}),
      ...(hasDescription ? { description: message.description } : {}),
    };
    const next = createDecoder();
    next.configure(decoderConfig);
    if (token !== generation) {
      next.close();
      return;
    }
    adopt(next, message);
    onError('');
  }

  async function configureH265(message, token) {
    for (const candidate of hevcCodecCandidates(message.description || new Uint8Array(0))) {
      if (token !== generation) return;
      const decoderConfig = {
        codec: candidate.codec,
        hardwareAcceleration: candidate.software ? 'no-preference' : preferHardware ? 'prefer-hardware' : 'no-preference',
        optimizeForLatency: true,
        ...(message.width > 0 ? { codedWidth: message.width } : {}),
        ...(message.height > 0 ? { codedHeight: message.height } : {}),
        ...(candidate.description ? { description: candidate.description } : {}),
      };
      const support = await VideoDecoder.isConfigSupported(decoderConfig).catch(() => null);
      if (token !== generation) return;
      if (!support?.supported) continue;
      const next = createDecoder();
      try {
        next.configure(decoderConfig);
      } catch (_) {
        next.close();
        continue;
      }
      adopt(next, message);
      onError('');
      return;
    }
    onError('这台 iPhone 发的是 H.265，但当前浏览器解不了，请在显示设置里改回 H.264。');
    onNeedKeyframe();
  }

  function pushFrame(message) {
    if (!decoder) return;
    lastArrival = performance.now();
    // A deep decode queue means we are behind: skip predictive frames, keep keyframes.
    if (!message.keyframe && decoder.decodeQueueSize > MAX_DECODE_QUEUE) {
      dropped++;
      return;
    }
    const data = usesDescriptionPerDecoder ? annexBToAvcc(message.data) : message.data;
    if (data.length === 0) return;
    try {
      decoder.decode(
        new EncodedVideoChunk({
          type: message.keyframe ? 'key' : 'delta',
          timestamp: message.pts,
          data,
        }),
      );
      framesDecoded++;
    } catch (error) {
      fail(`解码失败：${error.message || error}`);
    }
  }

  function paint(frame) {
    framesRendered++;
    clearWatchdog();
    const context = canvas.getContext('2d');
    if (!context) return;
    if (frame.displayWidth !== lastSize.width || frame.displayHeight !== lastSize.height) {
      resize(frame.displayWidth, frame.displayHeight);
    }
    context.drawImage(frame, 0, 0, canvas.width, canvas.height);
    onFrame(framesRendered);
  }

  function resize(width, height) {
    if (!(width > 0 && height > 0)) return;
    lastSize = { width, height };
    canvas.width = width;
    canvas.height = height;
    onResize(width, height);
  }

  function fail(reason) {
    closeDecoder();
    if (preferHardware) {
      // Hardware decoders fail more often than software ones on embedded browsers.
      preferHardware = false;
      if (config) {
        void configure(config);
        return;
      }
    }
    onError(reason);
    onNeedKeyframe();
  }

  function armWatchdog() {
    clearWatchdog();
    watchdog = window.setTimeout(() => {
      watchdog = null;
      if (!decoder) return;
      if (framesRendered === 0) {
        onNeedKeyframe();
        armWatchdog();
      }
    }, STALL_TIMEOUT_MS);
  }

  function clearWatchdog() {
    if (watchdog !== null) {
      window.clearTimeout(watchdog);
      watchdog = null;
    }
  }

  function closeDecoder() {
    clearWatchdog();
    const old = decoder;
    decoder = null;
    if (old) {
      try {
        old.close();
      } catch (_) {}
    }
  }

  return {
    supported,
    /** Feeds one parsed WCP1 packet. */
    push(message) {
      if (!message) return;
      if (message.type === 'config') void configure(message);
      else if (message.type === 'frame') pushFrame(message);
    },
    close() {
      generation++;
      closeDecoder();
      config = null;
    },
    stats() {
      return {
        framesDecoded,
        framesRendered,
        dropped,
        queue: decoder?.decodeQueueSize ?? 0,
        lastArrival,
        size: lastSize,
        prefersHardware: preferHardware,
      };
    },
  };
}

/** Builds the WebCodecs codec string from an AVCDecoderConfigurationRecord. */
export function avcCodecString(avcC) {
  if (!avcC || avcC.length < 4) return 'avc1.42E01E';
  const hex = value => value.toString(16).padStart(2, '0');
  return `avc1.${hex(avcC[1])}${hex(avcC[2])}${hex(avcC[3])}`;
}

/**
 * Rewrites one Annex B access unit as 4-byte-length-prefixed NAL units (AVCC), which is what a
 * `VideoDecoder` configured with a `description` expects. Ported from PlayPort's `protocol.ts`.
 */
export function annexBToAvcc(data) {
  const units = [];
  let index = 0;
  while (index + 3 < data.length) {
    let startLength = 0;
    if (data[index] === 0 && data[index + 1] === 0 && data[index + 2] === 1) {
      startLength = 3;
    } else if (
      index + 4 <= data.length &&
      data[index] === 0 && data[index + 1] === 0 && data[index + 2] === 0 && data[index + 3] === 1
    ) {
      startLength = 4;
    }
    if (startLength === 0) {
      index += 1;
      continue;
    }
    const nalStart = index + startLength;
    let nextStart = -1;
    let scan = nalStart;
    while (scan + 2 < data.length) {
      if (data[scan] === 0 && data[scan + 1] === 0) {
        if (data[scan + 2] === 1) {
          nextStart = scan;
          break;
        }
        if (scan + 3 < data.length && data[scan + 2] === 0 && data[scan + 3] === 1) {
          nextStart = scan;
          break;
        }
      }
      scan += 1;
    }
    const nalEnd = nextStart === -1 ? data.length : nextStart;
    if (nalEnd > nalStart) units.push({ offset: nalStart, length: nalEnd - nalStart });
    index = nalEnd;
  }
  if (units.length === 0) return data;
  let total = 0;
  for (const unit of units) total += 4 + unit.length;
  const output = new Uint8Array(total);
  const view = new DataView(output.buffer);
  let offset = 0;
  for (const unit of units) {
    view.setUint32(offset, unit.length);
    output.set(data.subarray(unit.offset, unit.offset + unit.length), offset + 4);
    offset += 4 + unit.length;
  }
  return output;
}

/**
 * Builds HEVC codec strings from an hvcC record, most specific first. WebCodecs wants `hvc1` plus
 * the record as description; the profile-compatibility flags are bit-reversed relative to hvcC.
 */
function hevcCodecCandidates(hvcC) {
  const candidates = [];
  if (hvcC.length >= 13) {
    const view = new DataView(hvcC.buffer, hvcC.byteOffset, hvcC.byteLength);
    const profileSpace = ['', 'A', 'B', 'C'][(hvcC[1] >> 6) & 0x03] ?? '';
    const tier = (hvcC[1] & 0x20) !== 0 ? 'H' : 'L';
    const profileIdc = hvcC[1] & 0x1f;
    const compat = reverseBits32(view.getUint32(2)) >>> 0;
    const levelIdc = hvcC[12];
    const constraints = [];
    for (let index = 6; index <= 11; index += 1) constraints.push(hvcC[index]);
    while (constraints.length > 0 && constraints[constraints.length - 1] === 0) constraints.pop();
    const constraintHex = constraints.map(value => value.toString(16).padStart(2, '0')).join('').toUpperCase();
    const base = `hvc1.${profileSpace}${profileIdc}.${compat.toString(16)}.${tier}${levelIdc}`;
    if (constraintHex) candidates.push({ codec: `${base}.${constraintHex}`, description: hvcC, software: false });
    candidates.push({ codec: base, description: hvcC, software: false });
    candidates.push({ codec: base, description: hvcC, software: true });
  }
  candidates.push({ codec: 'hvc1.1.6.L120.B0', description: hvcC.length ? hvcC : undefined, software: true });
  return candidates;
}

function reverseBits32(value) {
  let result = 0;
  for (let index = 0; index < 32; index += 1) {
    result = ((result << 1) | (value & 1)) >>> 0;
    value >>>= 1;
  }
  return result >>> 0;
}
