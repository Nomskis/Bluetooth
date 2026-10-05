import { CallEngine, RENEGOTIATE_CAPABILITY, randomId } from './call.js';
import { CHAT_CAPABILITY, QUICK_REPLIES } from './chat.js';
import { DelayTracker, isWeak, weakLabel } from './delay.js';
import { generateRoomCode, normalizeRoom } from './rooms.js';
import { SignalingClient } from './signaling.js';
import { RemoteVoiceWatcher } from './voice.js';
import { SCREEN_AV1_CAPABILITY, SCREEN_CAPABILITY, ScreenWatcher, decodesAv1 } from './screen.js';

const VERSION = '0.1.0';
const $ = (id) => document.getElementById(id);

const ui = {
  lobby: $('lobby'),
  form: $('join-form'),
  roomInput: $('room-input'),
  nameInput: $('name-input'),
  videoInput: $('video-input'),
  generate: $('generate-room'),
  joinButton: $('join-button'),
  lobbyError: $('lobby-error'),
  call: $('call'),
  remoteVideo: $('remote-video'),
  localVideo: $('local-video'),
  overlay: $('call-overlay'),
  status: $('call-status'),
  hint: $('call-hint'),
  copyLink: $('copy-link'),
  peerName: $('peer-name'),
  peerBadges: $('peer-badges'),
  delay: $('delay-readout'),
  roomLabel: $('room-label'),
  unmute: $('unmute-audio'),
  mic: $('toggle-mic'),
  camera: $('toggle-camera'),
  flip: $('flip-camera'),
  hangUp: $('hang-up'),
  toast: $('toast'),
  chatButton: $('toggle-chat'),
  chatUnread: $('chat-unread'),
  chatPanel: $('chat-panel'),
  chatClose: $('chat-close'),
  chatMessages: $('chat-messages'),
  chatQuick: $('chat-quick'),
  chatForm: $('chat-form'),
  chatInput: $('chat-input'),
  chatBubble: $('chat-bubble'),
  screenView: $('screen-view'),
  screenVideo: $('screen-video'),
  screenLabel: $('screen-label'),
};

const STATUS_TEXT = {
  connecting: 'Connecting to the server…',
  waiting: 'Waiting for the other person to join.',
  negotiating: 'Connecting…',
  reconnecting: 'Connection lost. Reconnecting…',
  ended: 'Call ended.',
  error: 'Could not join the call.',
};

function storage(kind) {
  try {
    return window[kind];
  } catch {
    return null;
  }
}

/** One peerId per browser tab, so a reload resumes the same slot. */
function tabPeerId() {
  const session = storage('sessionStorage');
  let id = session?.getItem('earshot.peerId');
  if (!id) {
    id = randomId('', 12);
    session?.setItem('earshot.peerId', id);
  }
  return id;
}

function signalingUrl() {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/ws`;
}

function inviteLink(room) {
  return `${location.origin}/r/${encodeURIComponent(room)}`;
}

let toastTimer;
function toast(text) {
  ui.toast.textContent = text;
  ui.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (ui.toast.hidden = true), 2500);
}

function showLobbyError(text) {
  ui.lobbyError.textContent = text;
  ui.lobbyError.hidden = !text;
}

// --- Lobby -------------------------------------------------------------------

// Room links are /r/<room>; one that got mangled on its way (a doubled slash, a
// stray path in front, the room without /r/) still finds its room.
const roomFromPath =
  location.pathname.match(/\/r\/([^/]+)\/?$/) ?? location.pathname.match(/^\/+([A-Za-z0-9-]{3,64})\/?$/);
// An invite link names the room; otherwise offer the last one (handy from the home screen).
ui.roomInput.value = roomFromPath ? decodeURIComponent(roomFromPath[1]) : (storage('localStorage')?.getItem('earshot.lastRoom') ?? '');
ui.nameInput.value = storage('localStorage')?.getItem('earshot.name') ?? '';

// On Android, offer the app: it keeps Bluetooth earbuds on the music link,
// which a browser can't. Without the app installed, Chrome stays on this page.
const openApp = $('open-app');
// Without the app, the invite opens here: offer it, so the call can ring next time.
$('get-app').hidden = !/Android/i.test(navigator.userAgent);
function updateOpenApp() {
  const room = normalizeRoom(ui.roomInput.value);
  openApp.hidden = !(room && /Android/i.test(navigator.userAgent));
  if (openApp.hidden) return;
  const fallback = encodeURIComponent(location.href);
  const server = encodeURIComponent(location.origin);
  openApp.href = `intent://join/${encodeURIComponent(room)}?server=${server}#Intent;scheme=earshot;S.browser_fallback_url=${fallback};end`;
}
ui.roomInput.addEventListener('input', updateOpenApp);
updateOpenApp();

ui.generate.addEventListener('click', () => {
  ui.roomInput.value = generateRoomCode();
  updateOpenApp();
  showLobbyError('');
});

ui.form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const room = normalizeRoom(ui.roomInput.value);
  if (!room) {
    showLobbyError('Room codes are 3–64 letters, numbers or dashes.');
    return;
  }
  // Inside the tap, before any await, so Safari lets the talking cue's audio start.
  voice.prime();
  const name = ui.nameInput.value.trim();
  storage('localStorage')?.setItem('earshot.name', name);
  storage('localStorage')?.setItem('earshot.lastRoom', room);
  showLobbyError('');
  ui.joinButton.disabled = true;
  try {
    const stream = await getLocalMedia(ui.videoInput.checked);
    startCall(room, name, stream);
  } catch (err) {
    showLobbyError(describeMediaError(err));
  } finally {
    ui.joinButton.disabled = false;
  }
});

let facingMode = 'user';

async function getLocalMedia(withVideo) {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('insecure');
  }
  const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
  const video = withVideo
    ? { facingMode, width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } }
    : false;
  try {
    return await navigator.mediaDevices.getUserMedia({ audio, video });
  } catch (err) {
    // No camera (or it is busy): fall back to audio only rather than failing.
    if (withVideo && (err.name === 'NotFoundError' || err.name === 'NotReadableError')) {
      toast('Camera unavailable, joining with audio only');
      return navigator.mediaDevices.getUserMedia({ audio, video: false });
    }
    throw err;
  }
}

function describeMediaError(err) {
  if (err.message === 'insecure') {
    return 'Your browser blocks the camera and microphone on this page. Open it over https://.';
  }
  switch (err.name) {
    case 'NotAllowedError':
      return 'Camera and microphone permission was denied. Allow it in your browser settings and try again.';
    case 'NotFoundError':
      return 'No microphone found.';
    default:
      return `Could not start camera or microphone (${err.name || err.message}).`;
  }
}

// --- Call ----------------------------------------------------------------------

let engine = null;
let wakeLock = null;
let remoteMedia = null;

// The head-start cue: lights up as their voice is decoded, before your
// speakers or Bluetooth headphones play it.
const voice = new RemoteVoiceWatcher((speaking) => {
  ui.call.classList.toggle('speaking', speaking);
  renderBadges(remoteMedia);
});
const delayTracker = new DelayTracker();
let delayTimer = null;
let delayOpen = false;

function startCall(room, name, stream) {
  history.replaceState(null, '', `/r/${encodeURIComponent(room)}`);
  ui.lobby.hidden = true;
  ui.call.hidden = false;
  ui.roomLabel.textContent = room;
  ui.localVideo.srcObject = stream;
  ui.localVideo.hidden = stream.getVideoTracks().length === 0;
  ui.camera.hidden = stream.getVideoTracks().length === 0;
  updateToggle(ui.mic, false, 'Mute', 'Unmute');
  updateToggle(ui.camera, false, 'Camera', 'Camera');
  detectFlip(stream);

  const signaling = new SignalingClient(signalingUrl(), {
    type: 'join',
    room,
    peerId: tabPeerId(),
    name,
    client: {
      platform: 'web',
      version: VERSION,
      capabilities: [CHAT_CAPABILITY, RENEGOTIATE_CAPABILITY, SCREEN_CAPABILITY, ...(decodesAv1() ? [SCREEN_AV1_CAPABILITY] : [])],
    },
  });
  engine = new CallEngine(signaling, stream);
  const screens = new ScreenWatcher(signaling, engine);
  screens.addEventListener('screen', (e) => showScreen(e.detail.stream));

  engine.addEventListener('status', (e) => renderStatus(e.detail));
  engine.addEventListener('peer', (e) => {
    ui.peerName.textContent = e.detail ? e.detail.name || 'Guest' : 'Waiting…';
    renderBadges(null);
    renderChatButton();
  });
  engine.chat.addEventListener('change', renderChat);
  engine.chat.addEventListener('message', (e) => onChatMessage(e.detail));
  renderChat();
  engine.addEventListener('remote-media', (e) => renderBadges(e.detail));
  engine.addEventListener('remote-stream', (e) => {
    attachRemote(e.detail);
    voice.watch(e.detail);
  });
  engine.addEventListener('error', (e) => {
    const { code, message } = e.detail;
    if (code === 'room-full') toast('That room already has two people in it.');
    else toast(message || code);
  });
  signaling.addEventListener('state', (e) => {
    if (e.detail === 'reconnecting' && engine.status === 'connected') toast('Server connection lost, retrying…');
  });

  signaling.connect();
  renderStatus(engine.status);
  // Handy from the devtools console, and used by the end-to-end tests.
  window.earshot = { engine, signaling, voice, screens };
  requestWakeLock();
  clearInterval(delayTimer);
  delayTimer = setInterval(updateDelay, 2000);
}

/** "≈ 230 ms from their mouth to your ears"; tap for the parts. */
async function updateDelay() {
  if (!engine || engine.status !== 'connected') {
    ui.delay.hidden = true;
    return;
  }
  const stats = await engine.getStats();
  if (!stats) return;
  const d = delayTracker.update(stats.values(), voice.outputLatencyMs, engine.packetTimeMs);
  window.earshot.delay = d;
  if (d.totalMs === null) {
    ui.delay.hidden = true;
    return;
  }
  ui.delay.hidden = false;
  ui.delay.classList.toggle('weak', isWeak(d));
  const weak = weakLabel(d, engine.remotePeer?.name);
  const packets = d.packetMs > 10 ? `, ${d.packetMs} ms packets for a rough link` : '';
  ui.delay.textContent = (weak ? `${weak} · ` : '') + (delayOpen
    ? `Their phone ≈ ${d.senderMs} ms${packets} · network ${d.networkMs} ms${d.relayed ? ' via relay' : d.relayed === false ? ' direct' : ''}${d.lossPercent ? ` (${d.lossPercent.toFixed(1)}% lost)` : ''} · buffer ${d.jitterBufferMs} ms · your device ${d.outputMs} ms`
    : `≈ ${d.totalMs} ms from their mouth to your ears`);
}

ui.delay.addEventListener('click', () => {
  delayOpen = !delayOpen;
  updateDelay();
});

// Connecting for a long time usually means the networks block direct calls. Same wording as the app.
const STUCK_HINT_MS = 15_000;
let stuckTimer = null;

function hasRelay(iceServers) {
  return (iceServers ?? []).some((s) => [s.urls].flat().some((u) => /^turns?:/.test(u)));
}

function stuckHint() {
  return hasRelay(engine?.iceServers)
    ? 'This is taking a while. Check that both of you are online; switching one side between Wi-Fi and mobile data can help.'
    : 'This is taking a while. Some networks (mobile data, gym or office Wi-Fi) block direct calls, and this server has no TURN relay to get around that. Try both on home Wi-Fi, or add TURN to the server.';
}

function renderStatus(status) {
  const connected = status === 'connected';
  ui.overlay.hidden = connected;
  ui.status.textContent = STATUS_TEXT[status] ?? status;
  if (status === 'negotiating' || status === 'reconnecting') {
    stuckTimer ??= setTimeout(() => {
      ui.hint.textContent = stuckHint();
      ui.hint.hidden = false;
    }, STUCK_HINT_MS);
  } else {
    clearTimeout(stuckTimer);
    stuckTimer = null;
    ui.hint.hidden = true;
  }
  ui.copyLink.hidden = !(status === 'waiting' || status === 'connecting');
  if (status === 'ended' || status === 'error') {
    ui.copyLink.hidden = true;
    setTimeout(() => location.assign(`/r/${encodeURIComponent(ui.roomLabel.textContent)}`), 1500);
  }
}

function renderBadges(media) {
  remoteMedia = media;
  ui.peerBadges.replaceChildren();
  if (voice.speaking) {
    const talking = document.createElement('span');
    talking.className = 'badge talking';
    talking.textContent = 'Talking';
    ui.peerBadges.append(talking);
  }
  if (!media) return;
  const add = (text, cls = '') => {
    const span = document.createElement('span');
    span.className = `badge ${cls}`;
    span.textContent = text;
    ui.peerBadges.append(span);
  };
  if (media.micMuted) add('Muted');
  if (media.inPocket) add('Phone in pocket');
  else if (media.weakConnection) add('Video paused: weak connection');
  else if (media.cameraOff) add('Camera off');
  if (media.audioMode === 'hifi') add('Hi-Fi audio', 'hifi');
}

// --- Their shared screen ---------------------------------------------------------

const screenZoom = zoomable(ui.screenView, ui.screenVideo);

function showScreen(stream) {
  if (!stream) {
    ui.screenView.hidden = true;
    ui.screenVideo.srcObject = null;
    screenZoom.reset();
    return;
  }
  const name = engine?.remotePeer?.name;
  ui.screenLabel.textContent = name ? `${name}'s screen` : 'Their screen';
  ui.screenVideo.srcObject = stream;
  ui.screenView.hidden = false;
  ui.screenVideo.play().catch(() => {});
}

/**
 * Wheel or pinch to zoom, drag to move, double-click (or double-tap) to zoom in at a point or
 * back out. The video keeps its full resolution underneath, so zooming shows real pixels.
 */
function zoomable(container, video) {
  let scale = 1;
  let x = 0;
  let y = 0;
  const pointers = new Map();
  let pinch = null;

  function apply() {
    const w = container.clientWidth;
    const h = container.clientHeight;
    scale = Math.min(6, Math.max(1, scale));
    x = Math.min(0, Math.max(w - w * scale, x));
    y = Math.min(0, Math.max(h - h * scale, y));
    video.style.transform = scale === 1 ? '' : `translate(${x}px, ${y}px) scale(${scale})`;
  }
  function zoomAt(next, px, py) {
    const ratio = Math.min(6, Math.max(1, next)) / scale;
    x = px - (px - x) * ratio;
    y = py - (py - y) * ratio;
    scale *= ratio;
    apply();
  }
  function point(e) {
    const r = container.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top];
  }
  container.addEventListener('wheel', (e) => {
    e.preventDefault();
    const [px, py] = point(e);
    zoomAt(scale * Math.exp(-e.deltaY * 0.002), px, py);
  }, { passive: false });
  container.addEventListener('dblclick', (e) => {
    const [px, py] = point(e);
    if (scale > 1.05) {
      scale = 1;
      x = 0;
      y = 0;
      apply();
    } else {
      zoomAt(2.5, px, py);
    }
  });
  container.addEventListener('pointerdown', (e) => {
    pointers.set(e.pointerId, point(e));
    container.setPointerCapture(e.pointerId);
  });
  container.addEventListener('pointermove', (e) => {
    if (!pointers.has(e.pointerId)) return;
    const prev = pointers.get(e.pointerId);
    const now = point(e);
    pointers.set(e.pointerId, now);
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const dist = Math.hypot(a[0] - b[0], a[1] - b[1]);
      const mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
      if (pinch) zoomAt(scale * (dist / pinch), mid[0], mid[1]);
      pinch = dist;
    } else if (pointers.size === 1 && scale > 1) {
      x += now[0] - prev[0];
      y += now[1] - prev[1];
      apply();
    }
  });
  const up = (e) => {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinch = null;
  };
  container.addEventListener('pointerup', up);
  container.addEventListener('pointercancel', up);
  window.addEventListener('resize', apply);
  return {
    reset() {
      scale = 1;
      x = 0;
      y = 0;
      apply();
    },
  };
}

function attachRemote(stream) {
  if (!stream) {
    ui.remoteVideo.srcObject = null;
    return;
  }
  if (ui.remoteVideo.srcObject !== stream) ui.remoteVideo.srcObject = stream;
  ui.remoteVideo.play().then(
    () => (ui.unmute.hidden = true),
    () => (ui.unmute.hidden = false),
  );
}

// --- Chat ----------------------------------------------------------------------

let chatOpen = false;
let unread = 0;
let bubbleTimer;

const baseTitle = document.title;

function renderChatButton() {
  // The tab title counts unread messages, for when this tab is in the background.
  document.title = unread ? `(${unread}) ${baseTitle}` : baseTitle;
  ui.chatButton.hidden = !engine?.remoteHasChat;
  ui.chatButton.setAttribute('aria-expanded', String(chatOpen));
  ui.chatUnread.hidden = unread === 0;
  ui.chatUnread.textContent = unread > 9 ? '9+' : String(unread);
}

const STATUS_LABEL = { sending: 'Sending…', delivered: 'Delivered', failed: 'Not sent' };

function renderChat() {
  const messages = engine?.chat.messages ?? [];
  ui.chatMessages.replaceChildren(
    ...messages.map((m) => {
      const li = document.createElement('li');
      li.className = m.mine ? `mine ${m.status}` : 'theirs';
      li.textContent = m.text;
      if (m.mine) {
        const status = document.createElement('small');
        status.textContent = STATUS_LABEL[m.status] ?? '';
        li.append(status);
      }
      return li;
    }),
  );
  ui.chatMessages.scrollTop = ui.chatMessages.scrollHeight;
}

function onChatMessage(message) {
  if (chatOpen) return;
  unread++;
  renderChatButton();
  const name = engine.remotePeer?.name || 'They';
  ui.chatBubble.textContent = `${name}: ${message.text}`;
  ui.chatBubble.hidden = false;
  clearTimeout(bubbleTimer);
  bubbleTimer = setTimeout(() => (ui.chatBubble.hidden = true), 6000);
}

function setChatOpen(open) {
  chatOpen = open;
  ui.chatPanel.hidden = !open;
  if (open) {
    unread = 0;
    ui.chatBubble.hidden = true;
    renderChat();
    ui.chatInput.focus();
  }
  renderChatButton();
}

ui.chatQuick.replaceChildren(
  ...QUICK_REPLIES.map((text) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = text;
    button.addEventListener('click', () => engine?.chat.send(text));
    return button;
  }),
);
ui.chatButton.addEventListener('click', () => setChatOpen(!chatOpen));
ui.chatClose.addEventListener('click', () => setChatOpen(false));
ui.chatBubble.addEventListener('click', () => setChatOpen(true));
ui.chatForm.addEventListener('submit', (event) => {
  event.preventDefault();
  if (engine?.chat.send(ui.chatInput.value)) ui.chatInput.value = '';
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && chatOpen) setChatOpen(false);
});

// iOS Safari lays the keyboard over fixed elements instead of resizing the page
// (Chrome resizes, see the viewport meta), so lift the chat above it.
function liftChatAboveKeyboard() {
  const vv = window.visualViewport;
  if (!vv) return;
  const covered = Math.max(0, window.innerHeight - vv.height - vv.offsetTop);
  ui.chatPanel.style.bottom = covered > 0 ? `${covered}px` : '';
}
window.visualViewport?.addEventListener('resize', liftChatAboveKeyboard);
window.visualViewport?.addEventListener('scroll', liftChatAboveKeyboard);

ui.unmute.addEventListener('click', () => {
  voice.resume();
  ui.remoteVideo.play().then(() => (ui.unmute.hidden = true));
});

function updateToggle(button, pressed, label, pressedLabel) {
  button.setAttribute('aria-pressed', String(pressed));
  button.querySelector('span').textContent = pressed ? pressedLabel : label;
}

ui.mic.addEventListener('click', () => {
  const muted = !engine.localMedia.micMuted;
  engine.setMicMuted(muted);
  updateToggle(ui.mic, muted, 'Mute', 'Unmute');
});

ui.camera.addEventListener('click', () => {
  const off = !engine.localMedia.cameraOff;
  engine.setCameraOff(off);
  updateToggle(ui.camera, off, 'Camera', 'Camera');
});

async function detectFlip(stream) {
  if (stream.getVideoTracks().length === 0) return;
  const devices = await navigator.mediaDevices.enumerateDevices().catch(() => []);
  ui.flip.hidden = devices.filter((d) => d.kind === 'videoinput').length < 2;
}

ui.flip.addEventListener('click', async () => {
  const next = facingMode === 'user' ? 'environment' : 'user';
  try {
    const fresh = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { exact: next }, width: { ideal: 1280 }, height: { ideal: 720 } },
    });
    facingMode = next;
    await engine.replaceVideoTrack(fresh.getVideoTracks()[0]);
    ui.localVideo.classList.toggle('mirrored', facingMode === 'user');
  } catch {
    toast('Could not switch camera');
  }
});

ui.copyLink.addEventListener('click', async () => {
  const link = inviteLink(ui.roomLabel.textContent);
  try {
    if (navigator.share) {
      await navigator.share({ title: 'Join my Earshot call', url: link });
    } else {
      await navigator.clipboard.writeText(link);
      toast('Invite link copied');
    }
  } catch {
    // User cancelled the share sheet.
  }
});

ui.hangUp.addEventListener('click', () => {
  voice.stop();
  clearInterval(delayTimer);
  engine?.hangUp();
  for (const track of ui.localVideo.srcObject?.getTracks() ?? []) track.stop();
});

window.addEventListener('pagehide', () => engine?.hangUp());

async function requestWakeLock() {
  try {
    wakeLock = await navigator.wakeLock?.request('screen');
  } catch {
    wakeLock = null;
  }
}

document.addEventListener('visibilitychange', () => {
  // Browsers drop the wake lock when the tab is hidden; take it again on return.
  const inCall = engine && engine.status !== 'ended' && engine.status !== 'error';
  if (document.visibilityState === 'visible' && inCall && (!wakeLock || wakeLock.released)) requestWakeLock();
});
