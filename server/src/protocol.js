/**
 * Wire protocol, version 1. The full description lives in docs/protocol.md;
 * example messages live in protocol/fixtures/ and are shared with the Android
 * tests so every implementation agrees on the format.
 */

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
    case 'leave':
      return { type: 'leave' };
    case 'ping':
      return { type: 'ping' };
    default:
      throw new ProtocolError(ErrorCode.BAD_REQUEST, `unknown message type "${msg.type}"`);
  }
}
