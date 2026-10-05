/**
 * Wire protocol, version 1. The full description lives in docs/protocol.md;
 * example messages live in protocol/fixtures/ and are shared with the Android
 * tests so every implementation agrees on the format.
 */

import { INBOX_ADDRESS_PATTERN, INBOX_KEY_PATTERN } from './inbox.js';

export const PROTOCOL_VERSION = 1;

export const ROOM_PATTERN = /^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])$/;
export const PEER_ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

const MAX_NAME_LENGTH = 64;
const MAX_CLIENT_FIELD_LENGTH = 32;
const MAX_CAPABILITIES = 16;

export const ErrorCode = Object.freeze({
  BAD_REQUEST: 'bad-request',
  BAD_ROOM: 'bad-room',
  ROOM_FULL: 'room-full',
  NOT_IN_ROOM: 'not-in-room',
  UNKNOWN_PEER: 'unknown-peer',
  RATE_LIMITED: 'rate-limited',
});

export class ProtocolError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Room codes are case-insensitive; we store them lower-cased. */
export function normalizeRoom(room) {
  if (typeof room !== 'string') {
    throw new ProtocolError(ErrorCode.BAD_ROOM, 'room must be a string');
  }
  const normalized = room.trim().toLowerCase();
  if (!ROOM_PATTERN.test(normalized)) {
    throw new ProtocolError(
      ErrorCode.BAD_ROOM,
      'room must be 3-64 characters: letters, digits and dashes, not starting or ending with a dash',
    );
  }
  return normalized;
}

function cleanText(value, maxLength) {
  if (typeof value !== 'string') return '';
  // Drop control characters, collapse whitespace.
  // eslint-disable-next-line no-control-regex
  return value.replace(/[\u0000-\u001f\u007f]/g, '').replace(/\s+/g, ' ').trim().slice(0, maxLength);
}

function cleanClient(client) {
  if (!isPlainObject(client)) return { platform: 'unknown', version: '' };
  const capabilities = Array.isArray(client.capabilities)
    ? client.capabilities
        .filter((c) => typeof c === 'string')
        .slice(0, MAX_CAPABILITIES)
        .map((c) => cleanText(c, MAX_CLIENT_FIELD_LENGTH))
        .filter(Boolean)
    : [];
  return {
    platform: cleanText(client.platform, MAX_CLIENT_FIELD_LENGTH) || 'unknown',
    version: cleanText(client.version, MAX_CLIENT_FIELD_LENGTH),
    capabilities,
  };
}

/** A session description; a screen's offer or answer is a few kilobytes. */
const MAX_SDP_LENGTH = 32 * 1024;
const MID_PATTERN = /^[A-Za-z0-9_-]{1,16}$/;
/** Cloudflare's session ids, as handed out in `screen-offer`. */
const SESSION_PATTERN = /^[A-Za-z0-9_-]{8,128}$/;
/** The sharer's name for one attempt at publishing, echoed with its answer. */
const PUBLISH_ID_PATTERN = /^[A-Za-z0-9_-]{1,32}$/;

/** Why a call wasn't taken: turned down, or already on another call. */
const RING_DECLINE_REASONS = new Set(['declined', 'busy']);

/** Longest chat message, in UTF-16 units (the clients' limit too). */
export const MAX_MESSAGE_TEXT = 4000;
const MESSAGE_ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

function messageIdOf(msg) {
  if (typeof msg.id !== 'string' || !MESSAGE_ID_PATTERN.test(msg.id)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'id must be 8-64 URL-safe characters');
  }
  return msg.id;
}

function ringIdOf(msg) {
  if (typeof msg.ringId !== 'string' || !PEER_ID_PATTERN.test(msg.ringId)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'ringId must be 8-64 URL-safe characters');
  }
  return msg.ringId;
}

/**
 * Parses and validates one message sent by a client.
 * Returns a normalized message object or throws ProtocolError.
 */
export function parseClientMessage(raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'message is not valid JSON');
  }
  if (!isPlainObject(msg) || typeof msg.type !== 'string') {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'message must be an object with a "type"');
  }

  switch (msg.type) {
    case 'join': {
      if (typeof msg.peerId !== 'string' || !PEER_ID_PATTERN.test(msg.peerId)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'peerId must be 8-64 URL-safe characters');
      }
      return {
        type: 'join',
        room: normalizeRoom(msg.room),
        peerId: msg.peerId,
        name: cleanText(msg.name, MAX_NAME_LENGTH),
        client: cleanClient(msg.client),
      };
    }
    case 'signal': {
      if (typeof msg.to !== 'string' || !PEER_ID_PATTERN.test(msg.to)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'signal needs a valid "to" peerId');
      }
      if (!isPlainObject(msg.data) || typeof msg.data.kind !== 'string') {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'signal needs a "data" object with a "kind"');
      }
      // The server relays signal payloads untouched; peers interpret them.
      return { type: 'signal', to: msg.to, data: msg.data };
    }
    case 'listen': {
      if (typeof msg.inbox !== 'string' || !INBOX_KEY_PATTERN.test(msg.inbox)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'inbox must be a 22-128 character URL-safe key');
      }
      return { type: 'listen', inbox: msg.inbox };
    }
    case 'ring': {
      if (typeof msg.to !== 'string' || !INBOX_ADDRESS_PATTERN.test(msg.to)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'ring needs a valid "to" inbox address');
      }
      if (msg.inbox !== undefined && msg.inbox !== null && (typeof msg.inbox !== 'string' || !INBOX_KEY_PATTERN.test(msg.inbox))) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'inbox must be a 22-128 character URL-safe key');
      }
      return {
        type: 'ring',
        to: msg.to,
        ringId: ringIdOf(msg),
        room: normalizeRoom(msg.room),
        name: cleanText(msg.name, MAX_NAME_LENGTH),
        video: msg.video === true,
        inbox: msg.inbox ?? null,
        // The caller takes a device that joins while still ringing as ringing (docs/protocol.md).
        preconnect: msg.preconnect === true,
      };
    }
    case 'ring-cancel':
      return { type: 'ring-cancel', ringId: ringIdOf(msg) };
    case 'ring-answer': {
      if (typeof msg.accepted !== 'boolean') {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'ring-answer needs "accepted": true or false');
      }
      const reason = msg.accepted ? undefined : RING_DECLINE_REASONS.has(msg.reason) ? msg.reason : 'declined';
      return { type: 'ring-answer', ringId: ringIdOf(msg), accepted: msg.accepted, reason };
    }
    case 'message': {
      // A chat message to someone's inbox, sent over our own listening inbox connection (docs/protocol.md).
      if (typeof msg.to !== 'string' || !INBOX_ADDRESS_PATTERN.test(msg.to)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'message needs a valid "to" inbox address');
      }
      if (typeof msg.text !== 'string' || msg.text.trim().length === 0 || msg.text.length > MAX_MESSAGE_TEXT) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, `message text must be 1-${MAX_MESSAGE_TEXT} characters`);
      }
      return { type: 'message', to: msg.to, id: messageIdOf(msg), text: msg.text, name: cleanText(msg.name, MAX_NAME_LENGTH) };
    }
    case 'message-ack':
    case 'message-read': {
      if (typeof msg.to !== 'string' || !INBOX_ADDRESS_PATTERN.test(msg.to)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, `${msg.type} needs a valid "to" inbox address`);
      }
      return { type: msg.type, to: msg.to, id: messageIdOf(msg) };
    }
    case 'screen-publish': {
      // The sharer's offer for Cloudflare, with the screen on transceiver `mid` (docs/protocol.md).
      if (typeof msg.sdp !== 'string' || msg.sdp.length === 0 || msg.sdp.length > MAX_SDP_LENGTH) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-publish needs an "sdp" offer');
      }
      if (typeof msg.mid !== 'string' || !MID_PATTERN.test(msg.mid)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-publish needs the screen\'s "mid"');
      }
      if (msg.id !== undefined && (typeof msg.id !== 'string' || !PUBLISH_ID_PATTERN.test(msg.id))) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-publish "id" must be a short name');
      }
      // The shared app's sound, when there is some, on transceiver `audioMid`.
      if (msg.audioMid !== undefined && (typeof msg.audioMid !== 'string' || !MID_PATTERN.test(msg.audioMid) || msg.audioMid === msg.mid)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-publish "audioMid" must be the sound\'s own mid');
      }
      return {
        type: 'screen-publish',
        sdp: msg.sdp,
        mid: msg.mid,
        ...(msg.audioMid !== undefined ? { audioMid: msg.audioMid } : {}),
        ...(msg.id !== undefined ? { id: msg.id } : {}),
      };
    }
    case 'screen-answer': {
      if (typeof msg.sdp !== 'string' || msg.sdp.length === 0 || msg.sdp.length > MAX_SDP_LENGTH) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-answer needs an "sdp" answer');
      }
      if (typeof msg.watch !== 'string' || !SESSION_PATTERN.test(msg.watch)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, 'screen-answer needs the "watch" session it answers');
      }
      return { type: 'screen-answer', watch: msg.watch, sdp: msg.sdp };
    }
    case 'screen-live':
    case 'screen-watch':
    case 'screen-stop':
      return { type: msg.type };
    case 'leave':
      return { type: 'leave' };
    case 'ping':
      return { type: 'ping' };
    default:
      throw new ProtocolError(ErrorCode.BAD_REQUEST, `unknown message type "${msg.type}"`);
  }
}
