/**
 * One-to-one call logic for the browser. The Android app implements the same
 * algorithm in CallSession.kt; docs/protocol.md describes it.
 *
 * Roles: the peer with the higher `seq` (the one that joined later) sends
 * offers. That removes offer glare without extra coordination. Every peer
 * connection gets a random `session` id so late messages from an old
 * connection can be told apart from the current one.
 *
 * Events:
 *   'status'        detail = 'connecting' | 'waiting' | 'negotiating' | 'connected' | 'reconnecting' | 'ended' | 'error'
 *   'remote-stream' detail = MediaStream | null
 *   'peer'          detail = { peerId, name, client, seq } | null
 *   'remote-media'  detail = { micMuted, cameraOff, audioMode }
 *   'error'         detail = { code, message }
 *
 * Text chat lives in `engine.chat` (a ChatLog, see chat.js); every peer
 * connection carries its data channel.
 */

import { CHAT_CAPABILITY, CHAT_CHANNEL, ChatLog } from './chat.js';
import { firstAudioCodec, opusMaxAverageBitrate, preferHdVoice, preferLowLatencyAudio, preferRedundantAudio } from './sdp.js';

const ICE_RECOVERY_DELAY_MS = 4000;
const OFFER_TIMEOUT_MS = 10_000;
const REQUEST_OFFER_DELAY_MS = 1500;

export function randomId(prefix = '', bytes = 9) {
  const raw = crypto.getRandomValues(new Uint8Array(bytes));
  const b64 = btoa(String.fromCharCode(...raw)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return prefix ? `${prefix}-${b64}` : b64;
}

function isHealthy(pc) {
  return pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed';
}

/** Our tweaks to every description we send: 10 ms packets and HD voice. */
function tune(sdp) {
  return preferHdVoice(preferLowLatencyAudio(sdp));
}

export class CallEngine extends EventTarget {
  #signaling;
  #localStream;
  #iceServers = [];
  #mySeq = 0;
  #remote = null;
  #pc = null;
  #session = null;
  #pendingCandidates = [];
  #remoteStream = null;
  #remoteMedia = { micMuted: false, cameraOff: false, audioMode: null };
  #localMedia = { micMuted: false, cameraOff: false };
  #status = 'connecting';
  #ops = Promise.resolve();
  #recoveryTimer = null;
  #offerTimer = null;
  #requestOfferTimer = null;
  #lastOfferReceivedAt = 0;
  #lastPeerId = null;
  chat = new ChatLog({ newId: () => randomId('m') });

  /**
   * @param {import('./signaling.js').SignalingClient} signaling
   * @param {MediaStream} localStream
   */
  constructor(signaling, localStream) {
    super();
    this.#signaling = signaling;
    this.#localStream = localStream;
    signaling.addEventListener('message', (e) => this.#enqueue(() => this.#onMessage(e.detail)));
  }

  get status() {
    return this.#status;
  }

  get remotePeer() {
    return this.#remote;
  }

  /** The ICE servers the server handed out (STUN, and TURN when it has a relay). */
  get iceServers() {
    return this.#iceServers;
  }

  /** Older clients don't have chat; their join message doesn't list it. */
  get remoteHasChat() {
    return !!this.#remote?.client?.capabilities?.includes?.(CHAT_CAPABILITY);
  }

  get localMedia() {
    return { ...this.#localMedia };
  }

  setMicMuted(muted) {
    this.#localMedia.micMuted = muted;
    for (const track of this.#localStream.getAudioTracks()) track.enabled = !muted;
    this.#sendMediaState();
  }

  setCameraOff(off) {
    this.#localMedia.cameraOff = off;
    for (const track of this.#localStream.getVideoTracks()) track.enabled = !off;
    this.#sendMediaState();
  }

  /** Swaps the outgoing camera track, e.g. after flipping front/back. */
  async replaceVideoTrack(track) {
    for (const old of this.#localStream.getVideoTracks()) {
      this.#localStream.removeTrack(old);
      old.stop();
    }
    track.enabled = !this.#localMedia.cameraOff;
    this.#localStream.addTrack(track);
    const transceiver = this.#pc?.getTransceivers().find((t) => t.receiver.track?.kind === 'video');
    if (transceiver) await transceiver.sender.replaceTrack(track);
  }

  /** WebRTC stats for the current connection, or null when there is none. */
  async getStats() {
    return this.#pc ? this.#pc.getStats() : null;
  }

  /** What the current connection negotiated; for diagnostics and tests. */
  get negotiated() {
    const remote = this.#pc?.currentRemoteDescription?.sdp;
    return { audioCodec: firstAudioCodec(remote), opusBitrate: opusMaxAverageBitrate(remote) };
  }

  hangUp() {
    this.#closePeer();
    this.#remote = null;
    this.#signaling.close();
    this.#setStatus('ended');
  }

  // --- message handling -----------------------------------------------------

  /** Serializes all async negotiation steps so they never interleave. */
  #enqueue(fn) {
    this.#ops = this.#ops.then(fn).catch((err) => {
      console.error('[earshot] negotiation error', err);
    });
    return this.#ops;
  }

  async #onMessage(msg) {
    switch (msg.type) {
      case 'joined':
        return this.#onJoined(msg);
      case 'peer-joined':
        return this.#onPeerJoined(msg.peer);
      case 'peer-left':
        return this.#onPeerLeft(msg.peerId);
      case 'signal':
        return this.#onSignal(msg.from, msg.data);
      case 'error':
        this.dispatchEvent(new CustomEvent('error', { detail: msg }));
        if (msg.code === 'room-full' || msg.code === 'bad-room') {
          this.#signaling.close();
          this.#setStatus('error');
        }
        return;
      default:
        return;
    }
  }

  async #onJoined(msg) {
    this.#iceServers = msg.iceServers ?? [];
    // A rejoin brings fresh TURN credentials; a long call's next ICE restart should use them.
    try {
      this.#pc?.setConfiguration({ ...this.#pc.getConfiguration(), iceServers: this.#iceServers });
    } catch (err) {
      console.warn('[earshot] could not update ICE servers', err);
    }
    this.#mySeq = msg.seq;
    const peer = msg.peers?.[0] ?? null;
    if (!peer) {
      this.#closePeer();
      this.#setRemote(null);
      this.#setStatus('waiting');
      return;
    }
    if (this.#remote && this.#remote.peerId !== peer.peerId) this.#closePeer();
    this.#setRemote(peer);
    await this.#ensureNegotiated();
  }

  async #onPeerJoined(peer) {
    if (this.#remote?.peerId === peer.peerId && this.#remote.seq === peer.seq) return;
    // A fresh join always means a fresh connection, even from the same peerId.
    this.#closePeer();
    this.#setRemote(peer);
    this.#setStatus('negotiating');
    if (this.#isOfferer()) await this.#startSession();
  }

  #onPeerLeft(peerId) {
    if (this.#remote?.peerId !== peerId) return;
    this.#closePeer();
    this.#setRemote(null);
    this.#setStatus('waiting');
  }

  async #onSignal(from, data) {
    if (!this.#remote || from !== this.#remote.peerId || !data) return;
    switch (data.kind) {
      case 'offer':
        return this.#onOffer(data);
      case 'answer':
        return this.#onAnswer(data);
      case 'candidate':
        return this.#onCandidate(data);
      case 'request-offer':
        return this.#onRequestOffer(data);
      case 'media-state':
        this.#remoteMedia = {
          micMuted: !!data.micMuted,
          cameraOff: !!data.cameraOff,
          audioMode: data.audioMode ?? null,
        };
        this.dispatchEvent(new CustomEvent('remote-media', { detail: { ...this.#remoteMedia } }));
        return;
      default:
        // Unknown kinds come from newer clients; ignore them.
        return;
    }
  }

  async #onOffer(data) {
    if (this.#isOfferer()) {
      console.warn('[earshot] ignoring offer: this side is the offerer');
      return;
    }
    this.#lastOfferReceivedAt = Date.now();
    let pc = this.#pc;
    const fresh = !pc || this.#session !== data.session;
    if (fresh) {
      this.#closePeer();
      this.#session = data.session;
      pc = this.#pc = this.#createPeerConnection();
      this.#setStatus('negotiating');
    }
    await pc.setRemoteDescription({ type: 'offer', sdp: data.sdp });
    if (this.#pc !== pc) return;
    if (fresh) this.#addLocalTracks(pc);
    preferRedundantAudio(pc);
    const answer = await pc.createAnswer();
    if (this.#pc !== pc) return;
    await pc.setLocalDescription(answer);
    if (this.#pc !== pc) return;
    this.#sendSignal({ kind: 'answer', session: this.#session, sdp: tune(pc.localDescription.sdp) });
    await this.#flushCandidates(pc);
  }

  async #onAnswer(data) {
    const pc = this.#pc;
    if (!pc || data.session !== this.#session || pc.signalingState !== 'have-local-offer') return;
    clearTimeout(this.#offerTimer);
    await pc.setRemoteDescription({ type: 'answer', sdp: data.sdp });
    await this.#flushCandidates(pc);
  }

  async #onCandidate(data) {
    const pc = this.#pc;
    if (!pc || data.session !== this.#session || !data.candidate) return;
    if (pc.remoteDescription) {
      await pc.addIceCandidate(data.candidate).catch((err) => console.warn('[earshot] bad candidate', err));
    } else {
      this.#pendingCandidates.push(data.candidate);
    }
  }

  async #onRequestOffer(data) {
    if (!this.#isOfferer()) return;
    const pc = this.#pc;
    if (pc && data.session === this.#session && pc.signalingState === 'stable' && pc.remoteDescription) {
      await this.#sendOffer(pc, true);
    } else {
      await this.#startSession();
    }
  }

  // --- negotiation ----------------------------------------------------------

  #isOfferer() {
    return !!this.#remote && this.#mySeq > this.#remote.seq;
  }

  async #ensureNegotiated() {
    const pc = this.#pc;
    if (pc && isHealthy(pc)) return;
    if (this.#isOfferer()) {
      if (pc && pc.signalingState === 'stable' && pc.remoteDescription) {
        await this.#sendOffer(pc, true);
      } else {
        await this.#startSession();
      }
      return;
    }
    // We answer. Offers queued on the server arrive right after `joined`,
    // so wait a moment before asking for a new one.
    if (!pc) this.#setStatus('negotiating');
    const askedAt = Date.now();
    clearTimeout(this.#requestOfferTimer);
    this.#requestOfferTimer = setTimeout(() => {
      this.#enqueue(() => {
        if (this.#lastOfferReceivedAt > askedAt) return;
        if (this.#pc && isHealthy(this.#pc)) return;
        this.#sendSignal({ kind: 'request-offer', session: this.#session });
      });
    }, REQUEST_OFFER_DELAY_MS);
  }

  async #startSession() {
    this.#closePeer();
    this.#session = randomId('s');
    const pc = (this.#pc = this.#createPeerConnection());
    this.#addLocalTracks(pc);
    // Always offer to receive both kinds, even when we send no camera ourselves.
    if (this.#localStream.getVideoTracks().length === 0) pc.addTransceiver('video', { direction: 'recvonly' });
    if (this.#localStream.getAudioTracks().length === 0) pc.addTransceiver('audio', { direction: 'recvonly' });
    preferRedundantAudio(pc);
    this.#setStatus('negotiating');
    await this.#sendOffer(pc, false);
  }

  async #sendOffer(pc, iceRestart) {
    const session = this.#session;
    const offer = await pc.createOffer(iceRestart ? { iceRestart: true } : undefined);
    if (this.#pc !== pc) return;
    await pc.setLocalDescription(offer);
    if (this.#pc !== pc) return;
    this.#sendSignal({ kind: 'offer', session, sdp: tune(pc.localDescription.sdp) });
    clearTimeout(this.#offerTimer);
    this.#offerTimer = setTimeout(() => {
      this.#enqueue(async () => {
        if (this.#pc === pc && pc.signalingState === 'have-local-offer') {
          console.warn('[earshot] no answer, starting over');
          await this.#startSession();
        }
      });
    }, OFFER_TIMEOUT_MS);
  }

  async #flushCandidates(pc) {
    const pending = this.#pendingCandidates.splice(0);
    for (const candidate of pending) {
      await pc.addIceCandidate(candidate).catch((err) => console.warn('[earshot] bad candidate', err));
    }
  }

  #createPeerConnection() {
    const pc = new RTCPeerConnection({
      iceServers: this.#iceServers,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require',
    });
    const stream = new MediaStream();
    this.#remoteStream = stream;

    pc.addEventListener('icecandidate', ({ candidate }) => {
      if (candidate && this.#pc === pc) {
        this.#sendSignal({ kind: 'candidate', session: this.#session, candidate: candidate.toJSON() });
      }
    });
    pc.addEventListener('track', ({ track }) => {
      if (this.#pc !== pc) return;
      stream.addTrack(track);
      this.dispatchEvent(new CustomEvent('remote-stream', { detail: stream }));
    });
    pc.addEventListener('iceconnectionstatechange', () => this.#onIceState(pc));
    // Created on both sides before negotiating, so the offer carries it and nobody waits for the other.
    this.chat.attach(pc.createDataChannel(CHAT_CHANNEL.label, { negotiated: true, id: CHAT_CHANNEL.id, ordered: true }));
    return pc;
  }

  #addLocalTracks(pc) {
    for (const track of this.#localStream.getTracks()) pc.addTrack(track, this.#localStream);
  }

  #onIceState(pc) {
    if (pc !== this.#pc) return;
    const state = pc.iceConnectionState;
    if (state === 'connected' || state === 'completed') {
      clearTimeout(this.#recoveryTimer);
      this.#recoveryTimer = null;
      this.#setStatus('connected');
      this.#sendMediaState();
    } else if (state === 'disconnected') {
      this.#setStatus('reconnecting');
      clearTimeout(this.#recoveryTimer);
      this.#recoveryTimer = setTimeout(() => this.#enqueue(() => this.#recover(pc)), ICE_RECOVERY_DELAY_MS);
    } else if (state === 'failed') {
      this.#setStatus('reconnecting');
      this.#enqueue(() => this.#recover(pc));
    }
  }

  async #recover(pc) {
    if (pc !== this.#pc || isHealthy(pc)) return;
    if (this.#isOfferer()) {
      if (pc.signalingState === 'stable') await this.#sendOffer(pc, true);
      else await this.#startSession();
    } else {
      this.#sendSignal({ kind: 'request-offer', session: this.#session });
    }
  }

  #closePeer() {
    clearTimeout(this.#recoveryTimer);
    clearTimeout(this.#offerTimer);
    clearTimeout(this.#requestOfferTimer);
    if (this.#pc) {
      this.chat.detach();
      this.#pc.close();
      this.#pc = null;
    }
    this.#session = null;
    this.#pendingCandidates = [];
    if (this.#remoteStream) {
      this.#remoteStream = null;
      this.dispatchEvent(new CustomEvent('remote-stream', { detail: null }));
    }
  }

  // --- helpers ----------------------------------------------------------------

  #sendSignal(data) {
    if (!this.#remote) return;
    this.#signaling.send({ type: 'signal', to: this.#remote.peerId, data });
  }

  #sendMediaState() {
    if (!this.#remote) return;
    this.#sendSignal({
      kind: 'media-state',
      micMuted: this.#localMedia.micMuted,
      cameraOff: this.#localMedia.cameraOff,
      audioMode: 'standard',
    });
  }

  #setRemote(peer) {
    if (peer && this.#lastPeerId && peer.peerId !== this.#lastPeerId) this.chat.peerChanged();
    if (peer) this.#lastPeerId = peer.peerId;
    this.#remote = peer;
    if (!peer) this.#remoteMedia = { micMuted: false, cameraOff: false, audioMode: null };
    this.dispatchEvent(new CustomEvent('peer', { detail: peer }));
  }

  #setStatus(status) {
    if (this.#status === status || this.#status === 'ended') return;
    this.#status = status;
    this.dispatchEvent(new CustomEvent('status', { detail: status }));
  }
}
