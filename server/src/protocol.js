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

/**
 * A picture in a chat message, as base64: the app sends it at most 1600 px on its long side
 * and about 450 KB, so this leaves room.
 */
export const MAX_PHOTO_BASE64 = 900_000;
const PHOTO_TYPES = new Set(['image/jpeg', 'image/webp']);
const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;
const MAX_PHOTO_SIDE = 8192;

function photoOf(photo) {
  if (!isPlainObject(photo) || typeof photo.data !== 'string' || photo.data.length === 0 || photo.data.length > MAX_PHOTO_BASE64) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, `photo needs its "data", base64, at most ${MAX_PHOTO_BASE64} characters`);
  }
  if (!BASE64_PATTERN.test(photo.data)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'photo "data" must be base64');
  }
  if (!PHOTO_TYPES.has(photo.type)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'photo "type" must be image/jpeg or image/webp');
  }
  const side = (v) => Number.isInteger(v) && v > 0 && v <= MAX_PHOTO_SIDE;
  if (!side(photo.width) || !side(photo.height)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'photo needs its "width" and "height" in pixels');
  }
  return { data: photo.data, type: photo.type, width: photo.width, height: photo.height };
}

/** A profile picture: the app sends 256 px square, a few tens of KB. */
export const MAX_PROFILE_PHOTO_BASE64 = 120_000;

/** Our profile, for a contact's phone: our picture (base64 JPEG), or that we took it away. */
function profileOf(profile) {
  if (!isPlainObject(profile)) throw new ProtocolError(ErrorCode.BAD_REQUEST, 'profile must be an object');
  if (profile.photo !== undefined) {
    if (typeof profile.photo !== 'string' || profile.photo.length === 0 || profile.photo.length > MAX_PROFILE_PHOTO_BASE64 || !BASE64_PATTERN.test(profile.photo)) {
      throw new ProtocolError(ErrorCode.BAD_REQUEST, `profile photo must be base64, at most ${MAX_PROFILE_PHOTO_BASE64} characters`);
    }
    return { photo: profile.photo };
  }
  if (profile.removed === true) return { removed: true };
  throw new ProtocolError(ErrorCode.BAD_REQUEST, 'profile needs a "photo", or "removed": true');
}

/** How much of the message a reply quotes travels with it, for a phone that no longer has it. */
export const MAX_REPLY_QUOTE = 300;

/**
 * The message a chat message answers: its `id`, whose it is from the sender's side (`me`, the
 * sender's own; `you`, the recipient's), and a short quote of it.
 */
function replyOf(reply) {
  if (!isPlainObject(reply) || typeof reply.id !== 'string' || !MESSAGE_ID_PATTERN.test(reply.id)) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'reply needs the "id" of the message it answers');
  }
  if (reply.sender !== 'me' && reply.sender !== 'you') {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, 'reply "sender" must be "me" or "you"');
  }
  return {
    id: reply.id,
    sender: reply.sender,
    // One line: line breaks become spaces.
    text: typeof reply.text === 'string' ? cleanText(reply.text.replace(/[\r\n\t]/g, ' '), MAX_REPLY_QUOTE) : '',
    ...(reply.photo === true ? { photo: true } : {}),
  };
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
      const id = messageIdOf(msg);
      const name = cleanText(msg.name, MAX_NAME_LENGTH);
      // Our profile picture for their phone, not a chat message; nothing else.
      if (msg.profile !== undefined) {
        if (msg.text !== undefined || msg.reply !== undefined || msg.photo !== undefined || msg.unsend !== undefined) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'a profile carries nothing else');
        }
        return { type: 'message', to: msg.to, id, name, profile: profileOf(msg.profile) };
      }
      // Delete for everyone: withdraws one of the sender's earlier messages, `unsend`; nothing else.
      if (msg.unsend !== undefined) {
        if (typeof msg.unsend !== 'string' || !MESSAGE_ID_PATTERN.test(msg.unsend)) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'unsend must name one of your messages');
        }
        if (msg.text !== undefined || msg.reply !== undefined || msg.photo !== undefined) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'an unsend carries nothing else');
        }
        return { type: 'message', to: msg.to, id, name, unsend: msg.unsend };
      }
      // A picture's words are optional; a message without one needs some.
      const photo = msg.photo !== undefined ? photoOf(msg.photo) : null;
      const text = msg.text ?? '';
      if (typeof text !== 'string' || text.length > MAX_MESSAGE_TEXT || (!photo && text.trim().length === 0)) {
        throw new ProtocolError(ErrorCode.BAD_REQUEST, `message text must be 1-${MAX_MESSAGE_TEXT} characters`);
      }
      return {
        type: 'message',
        to: msg.to,
        id,
        text,
        name,
        ...(msg.reply !== undefined ? { reply: replyOf(msg.reply) } : {}),
        ...(photo ? { photo } : {}),
      };
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
