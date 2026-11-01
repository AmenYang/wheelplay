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

async function pair(code) {
  const response = await fetch('/pair/code', {
    method: 'POST',
    headers: { 'x-pair-code': code },
    cache: 'no-store',
  });
  if (response.status === 401) throw new Error('配对码不对，看一下手机主界面上显示的 6 位数字。');
  if (!response.ok) throw new Error(`配对失败（HTTP ${response.status}）`);
  const data = await response.json();
  if (!data.token) throw new Error('配对失败：没有拿到令牌');
  return data.token;
}

function open() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${scheme}://${location.host}/stream?token=${encodeURIComponent(token)}&video=webcodecs`;
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
  retryTimer = setTimeout(() => {
    retryTimer = null;
    open();
  }, delay);
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
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  if (x < -0.02 || x > 1.02 || y < -0.02 || y > 1.02) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

function showHud() {
  hud.classList.remove('low');
  if (hudTimer !== null) window.clearTimeout(hudTimer);
  hudTimer = window.setTimeout(() => hud.classList.add('low'), 4000);
}

canvas.addEventListener('pointerdown', event => {
  audio.unlock();
  showHud();
  const point = pointFor(event);
  if (!point) return;
  try {
    canvas.setPointerCapture(event.pointerId);
  } catch (_) {}
  pointers.set(event.pointerId, { id: event.pointerId, ...point });
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
    open();
  } catch (error) {
    setMessage(error.message || String(error));
    connectButton.disabled = false;
  }
});

window.addEventListener('pagehide', () => {
  closing = true;
  clearInterval(heartbeat);
  try {
    socket?.close();
  } catch (_) {}
  audio.close();
  player.close();
});

if (!(await ensureSecureContext())) {
  connectButton.disabled = true;
}
