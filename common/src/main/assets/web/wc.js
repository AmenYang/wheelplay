import { createBrowserAudio } from './browser-audio.js';
import { createWebCodecsPlayer, isWebCodecsPacket, parsePacket } from './webcodecs-player.js';

const $ = id => document.getElementById(id);
const canvas = $('video');
const stage = $('stage');
const panel = $('connect');
const connectButton = $('connect-button');
const message = $('message');
const statusText = $('status');
const hud = $('hud');

let socket = null;
let token = null;
let attempts = 0;
let retryTimer = null;
let heartbeat = null;
let hudTimer = null;
let firstFrameSent = false;
let closing = false;
// Timestamp of the last `wc-start` request; the host answers every ping with a status, so a
// blind re-request on each status would fire every two seconds.
let lastStartAt = 0;

// CarPlay resolution, used to turn pointer positions into normalized coordinates.
let width = 0;
let height = 0;

const pointers = new Map();
let movePending = false;

// Remembered device credentials, shared with the main page under the same storage key.
const devicePreference = 'wheelplay.browserDevice';
let deviceCredential = null;
let storageAvailable = false;
try {
  const stored = window.localStorage.getItem(devicePreference);
  storageAvailable = true;
  if (stored) {
    const device = JSON.parse(stored);
    if (typeof device.id === 'string' && typeof device.secret === 'string' &&
        device.id.length === 32 && device.secret.length === 32) {
      deviceCredential = device;
    }
  }
} catch (_) {}

function setMessage(text) {
  message.textContent = text || '';
}

function setStatus(text) {
  statusText.textContent = text || '';
}

const player = createWebCodecsPlayer({
  canvas,
  onResize: (w, h) => {
    width = w;
    height = h;
  },
  onNeedKeyframe: () => send({ type: 'wc-keyframe' }),
  onError: text => {
    if (text) setMessage(text);
  },
  onFrame: () => {
    if (firstFrameSent) return;
    firstFrameSent = true;
    // Tells the host to stop sending JPEG stills and to remember we are displaying.
    send({ type: 'wc-ready' });
    setStatus('已连接（WebCodecs）');
  },
});

/**
 * WebCodecs is only exposed in a secure context, and a LAN http:// address is not one. The host
 * also serves the same page over HTTPS with its local certificate, so hop there before pairing.
 */
async function ensureSecureContext() {
  if (window.isSecureContext) return true;
  setMessage('正在切换到 HTTPS：WebCodecs 只能在安全上下文里使用。');
  try {
    const info = await (await fetch('/tls.json', { cache: 'no-store' })).json();
    if (info.available && Number.isInteger(info.port) && info.port > 0) {
      location.replace(`https://${location.hostname}:${info.port}/wc`);
      return false;
    }
    setMessage('这个地址不是安全上下文，而且车机没有开 HTTPS。请在手机设置里启用 HTTPS 后再打开。');
  } catch (_) {
    setMessage('这个地址不是安全上下文：请用 https:// 地址打开本页，否则浏览器不会提供 WebCodecs。');
  }
  return false;
}

const audio = createBrowserAudio({
  send: payload => send(payload),
  sendBinary: packet => {
    if (!socket || socket.readyState !== WebSocket.OPEN || socket.bufferedAmount > 8192) return false;
    try {
      socket.send(packet);
      return true;
    } catch (_) {
      return false;
    }
  },
  onState: ({ error }) => {
    if (error) setMessage(error);
  },
});

function send(payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  try {
    socket.send(JSON.stringify(payload));
  } catch (_) {}
}

async function pairApi(path, request = null) {
  const headers = request?.token ? { 'X-Pair-Token': request.token } :
    request?.device ? { 'X-Device-Id': request.device.id, 'X-Device-Secret': request.device.secret } :
    { 'X-Pair-Code': request?.code };
  const response = await fetch(`/pair/${path}`, { method: 'POST', headers, cache: 'no-store' });
  if (!response.ok) {
    const error = new Error(response.status === 401 ? 'expired' : 'unavailable');
    error.status = response.status;
    throw error;
  }
  return await response.json();
}

async function pair(code) {
  let data;
  try {
    data = await pairApi('code', { code });
  } catch (error) {
    if (error.status === 401) throw new Error('配对码不对，看一下手机主界面上显示的 6 位数字。');
    throw new Error(`配对失败（HTTP ${error.status || ''}）`);
  }
  if (!data.token) throw new Error('配对失败：没有拿到令牌');
  return data.token;
}

function remember(token) {
  if (!storageAvailable) return;
  pairApi('remember', { token })
    .then(device => {
      deviceCredential = device;
      try { window.localStorage.setItem(devicePreference, JSON.stringify(device)); } catch (_) {}
    })
    .catch(() => {});
}

async function autoConnect() {
  if (!deviceCredential) {
    connectButton.disabled = false;
    return;
  }
  connectButton.disabled = true;
  setMessage('');
  try {
    const result = await pairApi('resume', { device: deviceCredential });
    if (!result.token) throw new Error('no-token');
    token = result.token;
    open();
  } catch (error) {
    connectButton.disabled = false;
    if (error.status === 401) {
      deviceCredential = null;
      try { window.localStorage.removeItem(devicePreference); } catch (_) {}
      setMessage('记住的设备已失效，请输入配对码。');
    } else {
      setMessage('自动恢复配对失败，请输入配对码。');
    }
  }
}

function open() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${scheme}://${location.host}/stream?token=${encodeURIComponent(token)}&video=webcodecs`;
  closing = false;
  setStatus('连接中…');
  const ws = new WebSocket(url);
  ws.binaryType = 'arraybuffer';
  socket = ws;
  const connectTimer = setTimeout(() => {
    try {
      ws.close();
    } catch (_) {}
  }, 8000);

  ws.onopen = () => {
    clearTimeout(connectTimer);
    if (socket !== ws) return;
    attempts = 0;
    firstFrameSent = false;
    panel.hidden = true;
    stage.hidden = false;
    showHud();
    lastViewportSignature = '';
    lastReportedDark = null;
    scheduleViewportReport();
    reportAppearance();
    watchAppearance(true);
    audio.attach();
    // Audio routing is one-way: the host pushes `audio-route`, and the page only reports readiness
    // through `audio-ready`. Sending `audio-route` here is an unknown control message and the host
    // answers it by dropping the socket.
    send({ type: 'wc-start' });
    clearInterval(heartbeat);
    heartbeat = setInterval(() => send({ type: 'ping' }), 2000);
    setStatus('等待 iPhone 画面…');
  };

  ws.onmessage = event => {
    if (socket !== ws) return;
    if (typeof event.data === 'string') {
      handleControl(event.data);
      return;
    }
    if (audio.receive(event.data)) return;
    if (!isWebCodecsPacket(event.data)) return;
    player.push(parsePacket(event.data));
  };

  ws.onclose = () => {
    clearTimeout(connectTimer);
    clearInterval(heartbeat);
    watchAppearance(false);
    if (socket !== ws) return;
    socket = null;
    player.close();
    if (closing) return;
    scheduleRetry();
  };

  ws.onerror = () => {
    try {
      ws.close();
    } catch (_) {}
  };
}

function scheduleRetry() {
  if (retryTimer !== null) return;
  const delay = Math.min(500 * 2 ** attempts, 5000);
  attempts += 1;
  setStatus('连接断开，重试中…');
  retryTimer = window.setTimeout(async () => {
    retryTimer = null;
    // A token lives in the host process and expires after 12 hours, but this page can stay open
    // far longer than either. Re-derive one from the remembered device before every reconnect;
    // otherwise a host restart leaves the page retrying a dead token forever.
    if (deviceCredential) {
      try {
        const result = await pairApi('resume', { device: deviceCredential });
        if (result.token) token = result.token;
      } catch (error) {
        if (error.status === 401) {
          forgetDevice();
          return;
        }
      }
    }
    open();
  }, delay);
}

function forgetDevice() {
  token = null;
  deviceCredential = null;
  try { window.localStorage.removeItem(devicePreference); } catch (_) {}
  closing = true;
  stage.hidden = true;
  panel.hidden = false;
  connectButton.disabled = false;
  setStatus('');
  setMessage('记住的设备已失效，请输入配对码。');
}

function handleControl(text) {
  let data;
  try {
    data = JSON.parse(text);
  } catch (_) {
    return;
  }
  if (audio.control(data)) return;
  switch (data.type) {
    case 'status':
      if (data.width > 0 && data.height > 0) {
        width = data.width;
        height = data.height;
      }
      if (!data.webcodecs) {
        // Either the host has not started yet, or its video source was re-created. Only ask when a
        // source can actually serve frames, and never more than once every two seconds.
        if (data.sourceState !== 'stopped' && Date.now() - lastStartAt > 2000) {
          lastStartAt = Date.now();
          send({ type: 'wc-start' });
        }
        setStatus(data.stage || '等待 iPhone 画面…');
      }
      break;
    case 'wc-start':
      setStatus('已连接（WebCodecs）');
      break;
    case 'wc-stop':
      setStatus('视频中断，等待关键帧…');
      send({ type: 'wc-keyframe' });
      break;
    case 'busy':
      closing = true;
      setMessage(data.message || '另一台车机已经连上了。');
      setStatus('已被占用');
      break;
    case 'input-unavailable':
      setStatus('画面还没准备好接收触摸');
      break;
    default:
      break;
  }
}

function touchContacts() {
  return [...pointers.values()].map(point => ({ id: point.id, x: point.x, y: point.y, down: true }));
}

function sendTouch() {
  if (!pointers.size) return;
  send({ type: 'touch', contacts: touchContacts() });
}

function pointFor(event) {
  const rect = canvas.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0 || width <= 0 || height <= 0) return null;
  // The canvas is letterboxed by `object-fit: contain`; map into the visible picture area.
  const scale = Math.min(rect.width / width, rect.height / height);
  const w = width * scale, h = height * scale;
  const x = (event.clientX - rect.left - (rect.width - w) / 2) / w;
  const y = (event.clientY - rect.top - (rect.height - h) / 2) / h;
  if (x < -0.02 || x > 1.02 || y < -0.02 || y > 1.02) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

function showHud() {
  hud.classList.remove('hidden');
  if (hudTimer !== null) window.clearTimeout(hudTimer);
  hudTimer = window.setTimeout(() => hud.classList.add('hidden'), 10000);
}

// Negotiate the host's encode size to the stage's physical pixels, mirroring the main page, so
// the CarPlay source is rendered at the head unit's own aspect ratio instead of letterboxing.
let viewportTimer = null;
let lastViewportSignature = '';

// The head unit's own dark/light theme is what the driver is looking at. A car usually flips it
// from the headlights rather than from sunset, so it is a better answer to "is CarPlay dark?"
// than the phone's uiMode is. Report it whenever it changes, and let the host do the asking.
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
let lastReportedDark = null;
let appearanceTimer = null;

function reportAppearance() {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  const dark = darkQuery.matches;
  if (dark === lastReportedDark) return;
  lastReportedDark = dark;
  send({ type: 'appearance', dark });
}

/* A head-unit browser updates this value when the headlights decide the screen is dark, but it
 * does not reliably turn that into a `change` event, and a page that is not in front is not
 * resumed at all. Reading it on a timer cannot miss the transition; the event stays as the fast
 * path for browsers that do dispatch it. */
function watchAppearance(on) {
  if (appearanceTimer !== null) {
    window.clearInterval(appearanceTimer);
    appearanceTimer = null;
  }
  if (on) appearanceTimer = window.setInterval(reportAppearance, 1000);
}

if (darkQuery.addEventListener) darkQuery.addEventListener('change', reportAppearance);
else darkQuery.addListener?.(reportAppearance);
document.addEventListener('visibilitychange', () => { if (!document.hidden) reportAppearance(); });
window.addEventListener('focus', reportAppearance);
window.addEventListener('pageshow', reportAppearance);

function browserViewportSize(width, height) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  const ratio = width / height;
  if (ratio < 0.5 || ratio > 4) return null;
  const scale = Math.min(8192 / Math.max(width, height), Math.max(1, 320 / Math.min(width, height)));
  const even = value => Math.round(value / 2) * 2;
  const h = even(height * scale);
  const w = Math.max(Math.ceil(h / 4) * 2, Math.min(h * 4, even(width * scale)));
  if (!Number.isFinite(w) || !Number.isFinite(h) || w < 320 || h < 320 || w > 8192 || h > 8192) return null;
  return { width: w, height: h };
}

function scheduleViewportReport() {
  if (viewportTimer !== null) return;
  viewportTimer = window.setTimeout(() => {
    viewportTimer = null;
    if (!socket || socket.readyState !== WebSocket.OPEN || stage.hidden) return;
    const rect = stage.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const size = browserViewportSize(rect.width * dpr, rect.height * dpr);
    if (!size) return;
    const signature = `${size.width}x${size.height}`;
    if (signature === lastViewportSignature) return;
    lastViewportSignature = signature;
    send({ type: 'viewport', width: size.width, height: size.height });
  }, 180);
}

canvas.addEventListener('pointerdown', event => {
  audio.unlock();
  const point = pointFor(event);
  if (!point) return;
  try {
    canvas.setPointerCapture(event.pointerId);
  } catch (_) {}
  const used = new Set([...pointers.values()].map(p => p.id));
  let id = 0; while (used.has(id)) id++;
  pointers.set(event.pointerId, { id, ...point });
  sendTouch();
  event.preventDefault();
});

canvas.addEventListener('pointermove', event => {
  const current = pointers.get(event.pointerId);
  if (!current) return;
  const point = pointFor(event);
  if (!point) return;
  Object.assign(current, point);
  if (movePending) return;
  movePending = true;
  window.setTimeout(() => {
    movePending = false;
    sendTouch();
  }, 33);
});

function releasePointer(event) {
  const current = pointers.get(event.pointerId);
  if (!current) return;
  const point = pointFor(event) || current;
  pointers.delete(event.pointerId);
  send({
    type: 'touch',
    contacts: [...touchContacts(), { id: current.id, x: point.x, y: point.y, down: false }],
  });
}

canvas.addEventListener('pointerup', releasePointer);
canvas.addEventListener('pointercancel', releasePointer);
canvas.addEventListener('contextmenu', event => event.preventDefault());

$('fullscreen').addEventListener('click', () => {
  if (document.fullscreenElement) void document.exitFullscreen();
  else void stage.requestFullscreen?.().catch(() => {});
});

window.addEventListener('resize', scheduleViewportReport);
window.visualViewport?.addEventListener('resize', scheduleViewportReport);

$('connect-form').addEventListener('submit', async event => {
  event.preventDefault();
  const code = $('code').value.trim();
  if (!/^[0-9]{6}$/.test(code)) {
    setMessage('请输入 6 位配对码。');
    return;
  }
  connectButton.disabled = true;
  setMessage('');
  try {
    token = await pair(code);
    remember(token);
    open();
  } catch (error) {
    setMessage(error.message || String(error));
    connectButton.disabled = false;
  }
});

window.addEventListener('pagehide', () => {
  closing = true;
  clearInterval(heartbeat);
  watchAppearance(false);
  try {
    socket?.close();
  } catch (_) {}
  audio.close();
  player.close();
});

if (!(await ensureSecureContext())) {
  connectButton.disabled = true;
} else {
  autoConnect();
}
