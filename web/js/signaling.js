/**
 * WebSocket signaling client for the browser. Reconnects automatically and
 * re-sends `join` with the same peerId so the server resumes our slot.
 * Emits:
 *   'message'  detail = parsed server message
 *   'state'    detail = 'connecting' | 'open' | 'reconnecting' | 'closed'
 */
export class SignalingClient extends EventTarget {
  #url;
  #join;
  #ws = null;
  #queue = [];
  #attempt = 0;
  #retryTimer = null;
  #closed = false;

  /**
   * @param {string} url  ws:// or wss:// URL of the /ws endpoint
   * @param {object} join The join message to send on every (re)connect
   */
  constructor(url, join) {
    super();
    this.#url = url;
    this.#join = join;
  }

  get peerId() {
    return this.#join.peerId;
  }

  connect() {
    this.#closed = false;
    this.#open();
  }

  /** Sends a message, or queues it until the socket is back. */
  send(msg) {
    if (this.#ws?.readyState === WebSocket.OPEN) {
      this.#ws.send(JSON.stringify(msg));
    } else if (msg.type === 'signal') {
      this.#queue.push(msg);
      if (this.#queue.length > 100) this.#queue.shift();
    }
  }

  /** Drops the socket without a goodbye, as a flaky network would. Used by tests. */
  simulateNetworkDrop() {
    this.#ws?.close(4001, 'simulated network drop');
  }

  close() {
    this.#closed = true;
    clearTimeout(this.#retryTimer);
    if (this.#ws?.readyState === WebSocket.OPEN) {
      this.#ws.send(JSON.stringify({ type: 'leave' }));
    }
    this.#ws?.close(1000, 'bye');
    this.#ws = null;
    this.#emitState('closed');
  }

  #open() {
    this.#emitState(this.#attempt === 0 ? 'connecting' : 'reconnecting');
    const ws = new WebSocket(this.#url);
    this.#ws = ws;

    ws.addEventListener('open', () => {
      this.#attempt = 0;
      ws.send(JSON.stringify(this.#join));
      for (const msg of this.#queue.splice(0)) ws.send(JSON.stringify(msg));
      this.#emitState('open');
    });

    ws.addEventListener('message', (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      this.dispatchEvent(new CustomEvent('message', { detail: msg }));
    });

    ws.addEventListener('close', (event) => {
      if (this.#ws !== ws || this.#closed) return;
      if (event.code === 4000) {
        // Another tab/device took over this peerId. Don't fight it.
        this.#closed = true;
        this.#emitState('closed');
        return;
      }
      this.#scheduleReconnect();
    });
  }

  #scheduleReconnect() {
    this.#attempt += 1;
    const delay = Math.min(8000, 500 * 2 ** (this.#attempt - 1)) * (0.75 + Math.random() * 0.5);
    this.#emitState('reconnecting');
    this.#retryTimer = setTimeout(() => this.#open(), delay);
  }

  #emitState(state) {
    this.dispatchEvent(new CustomEvent('state', { detail: state }));
  }
}
