// Friendly, hard-to-guess room codes like "calm-otter-4821".

const ADJECTIVES = [
  'amber', 'bold', 'brave', 'bright', 'calm', 'clever', 'cosmic', 'crisp', 'eager', 'fancy',
  'fuzzy', 'gentle', 'golden', 'happy', 'jolly', 'kind', 'lively', 'lucky', 'mellow', 'merry',
  'mighty', 'misty', 'noble', 'plucky', 'proud', 'quick', 'quiet', 'rapid', 'rosy', 'shiny',
  'silver', 'sleek', 'snowy', 'sunny', 'swift', 'tidy', 'vivid', 'warm', 'wild', 'witty',
];

const NOUNS = [
  'badger', 'beacon', 'bison', 'canyon', 'cedar', 'comet', 'coral', 'crane', 'delta', 'ember',
  'falcon', 'fjord', 'forest', 'glacier', 'harbor', 'heron', 'island', 'lagoon', 'lynx', 'maple',
  'meadow', 'meteor', 'moose', 'nebula', 'orbit', 'otter', 'panda', 'pebble', 'puffin', 'quartz',
  'raven', 'reef', 'river', 'sparrow', 'summit', 'tiger', 'tundra', 'valley', 'walrus', 'willow',
];

function pick(list, random) {
  return list[random % list.length];
}

export function generateRoomCode() {
  const r = crypto.getRandomValues(new Uint32Array(3));
  const number = 1000 + (r[2] % 9000);
  return `${pick(ADJECTIVES, r[0])}-${pick(NOUNS, r[1])}-${number}`;
}

const ROOM_PATTERN = /^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])$/;

/** Mirrors the server's validation so we can show errors before connecting. */
export function normalizeRoom(input) {
  const room = (input ?? '').trim().toLowerCase().replace(/\s+/g, '-');
  return ROOM_PATTERN.test(room) ? room : null;
}
