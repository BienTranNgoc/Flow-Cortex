import { withLocale } from './locale';
import { emitNetworkSignal } from './networkSignals';
import { refreshAccessToken } from './session';
import { getToken } from './tokenStore';

const STREAM_PATH = '/api/v1/realtime/stream';
const BACKOFF_BASE_MS = 1000;
const BACKOFF_CAP_MS = 30_000;
/** Three missed server heartbeats (the hub pings every 15 s). */
const IDLE_TIMEOUT_MS = 45_000;
let nextStreamId = 0;

type Outcome = 'retry' | 'reauth' | 'stop';

export interface StreamEvent {
  id: string | null;
  event: string;
  data: string;
}

export interface EventStreamOptions {
  /** Topics to subscribe to, as the server names them (`run:<id>`, `user`). */
  topics: readonly string[];
  onEvent: (event: StreamEvent) => void;
  /** Called every time a connection is accepted, the first one and each reconnect. */
  onOpen?: () => void;
  /** Drops and reconnects a connection that delivers no bytes for this long. */
  idleTimeoutMs?: number;
}

export interface EventStream {
  /** Ends the stream for good: aborts the request and cancels any pending reconnect. */
  close: () => void;
}

/** Incremental Server-Sent Events parser; `EventSource` cannot send a bearer token. */
class SseParser {
  private buffer = '';
  private data: string[] = [];
  private eventName = '';
  private eventId: string | null = null;

  /** Feeds a decoded chunk and returns the events it completed. */
  feed(chunk: string): StreamEvent[] {
    this.buffer += chunk;
    const events: StreamEvent[] = [];
    // A trailing CR may be half of a CRLF whose LF arrives in the next chunk.
    const heldCr = this.buffer.endsWith('\r');
    const complete = heldCr ? this.buffer.slice(0, -1) : this.buffer;
    const lines = complete.split(/\r\n|\r|\n/);
    const rest = lines.pop() ?? '';
    this.buffer = heldCr ? `${rest}\r` : rest;
    for (const line of lines) {
      const event = this.line(line);
      if (event !== null) {
        events.push(event);
      }
    }
    return events;
  }

  private line(line: string): StreamEvent | null {
    if (line === '') {
      return this.dispatch();
    }
    if (line.startsWith(':')) {
      return null;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'data') {
      this.data.push(value);
    } else if (field === 'event') {
      this.eventName = value;
    } else if (field === 'id' && !value.includes('\0')) {
      this.eventId = value;
    }
    return null;
  }

  private dispatch(): StreamEvent | null {
    const event =
      this.data.length === 0
        ? null
        : { id: this.eventId, event: this.eventName || 'message', data: this.data.join('\n') };
    this.data = [];
    this.eventName = '';
    this.eventId = null;
    return event;
  }
}

/** Full-jitter exponential backoff: a random delay up to 1 s, 2 s, 4 s … capped at 30 s. */
function backoffDelay(failures: number): number {
  const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** failures);
  return Math.random() * ceiling;
}

/** Only a malformed (400), forbidden (403) or unknown (404) topic will not change on retry. */
function isFinalRefusal(status: number): boolean {
  return status === 400 || status === 403 || status === 404;
}

/**
 * Holds an authenticated event stream open. A dropped or completed connection reconnects after a
 * backoff with the last event id; a 401 refreshes the token once and retries once; a refused
 * topic or a second 401 ends the stream.
 */
export function openEventStream(options: EventStreamOptions): EventStream {
  const { topics, onEvent, onOpen, idleTimeoutMs = IDLE_TIMEOUT_MS } = options;
  const query = topics.map((topic) => `topic=${encodeURIComponent(topic)}`).join('&');
  const url = `${STREAM_PATH}?${query}`;
  let closed = false;
  let lastEventId: string | null = null;
  let controller: AbortController | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let wake: (() => void) | null = null;
  let idleTimer: ReturnType<typeof setTimeout> | null = null;

  const clearIdle = () => {
    if (idleTimer !== null) {
      clearTimeout(idleTimer);
      idleTimer = null;
    }
  };
  const streamId = (nextStreamId += 1);
  let down = false;

  function markDown(): void {
    if (!down) {
      down = true;
      emitNetworkSignal({ type: 'stream-down', streamId });
    }
  }

  function markUp(): void {
    if (down) {
      down = false;
      emitNetworkSignal({ type: 'stream-up', streamId });
    }
  }

  const sleep = (ms: number) =>
    new Promise<void>((resolve) => {
      wake = resolve;
      timer = setTimeout(resolve, ms);
    });

  /** Reads until the body ends or goes silent past the idle timeout; true when closed meanwhile. */
  async function pump(body: ReadableStream<Uint8Array>, onActivity: () => void): Promise<boolean> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser();
    // A path that dies without FIN or RST leaves read() pending for good; cancelling the reader
    // ends it, and aborting the request frees the socket.
    const armIdle = () => {
      clearIdle();
      idleTimer = setTimeout(() => {
        controller?.abort();
        reader.cancel().catch(() => undefined);
      }, idleTimeoutMs);
    };
    armIdle();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (closed) {
          return true;
        }
        if (done) {
          return false;
        }
        armIdle();
        onActivity();
        for (const event of parser.feed(decoder.decode(value, { stream: true }))) {
          if (event.id !== null) {
            lastEventId = event.id;
          }
          onEvent(event);
        }
      }
    } finally {
      clearIdle();
      reader.releaseLock();
    }
  }

  /** One connection attempt: connect, then pump until the body ends. */
  async function connectOnce(state: { refreshedOn401: boolean; failures: number }): Promise<Outcome> {
    controller = new AbortController();
    try {
      const headers = withLocale({ Accept: 'text/event-stream' });
      const token = getToken();
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
      }
      if (lastEventId !== null) {
        headers.set('Last-Event-ID', lastEventId);
      }
      const response = await fetch(url, { headers, signal: controller.signal });
      if (closed) {
        return 'stop';
      }
      if (response.status === 401) {
        if (state.refreshedOn401 || (await refreshAccessToken()) === null) {
          return 'stop';
        }
        state.refreshedOn401 = true;
        return 'reauth';
      }
      if (isFinalRefusal(response.status)) {
        return 'stop';
      }
      if (response.status === 429) {
        markUp();
        emitNetworkSignal({ type: 'rate-limited', waitSeconds: null });
      } else if (response.ok && response.body) {
        state.refreshedOn401 = false;
        markUp();
        onOpen?.();
        if (await pump(response.body, () => (state.failures = 0))) {
          return 'stop';
        }
      } else {
        markDown();
      }
    } catch {
      // A failed connect or a broken body is retried; only close() ends the loop.
      if (closed) {
        return 'stop';
      }
      markDown();
    }
    return 'retry';
  }

  async function run(): Promise<void> {
    const state = { refreshedOn401: false, failures: 0 };
    while (!closed) {
      const outcome = await connectOnce(state);
      if (outcome === 'stop') {
        return;
      }
      if (outcome === 'retry') {
        await sleep(backoffDelay(state.failures));
        state.failures += 1;
      }
    }
  }

  // However the loop ends (close, refused topic, second 401) the stream stops counting as down.
  void run().finally(markUp);

  return {
    close() {
      closed = true;
      markUp();
      controller?.abort();
      clearIdle();
      if (timer !== null) {
        clearTimeout(timer);
      }
      wake?.();
    },
  };
}
