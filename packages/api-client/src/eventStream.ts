import { withLocale } from './locale';
import { refreshAccessToken } from './session';
import { getToken } from './tokenStore';

const STREAM_PATH = '/api/v1/realtime/stream';
const BACKOFF_BASE_MS = 1000;
const BACKOFF_CAP_MS = 30_000;

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
  const { topics, onEvent, onOpen } = options;
  const query = topics.map((topic) => `topic=${encodeURIComponent(topic)}`).join('&');
  const url = `${STREAM_PATH}?${query}`;
  let closed = false;
  let lastEventId: string | null = null;
  let controller: AbortController | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let wake: (() => void) | null = null;

  const sleep = (ms: number) =>
    new Promise<void>((resolve) => {
      wake = resolve;
      timer = setTimeout(resolve, ms);
    });

  /** Reads until the server ends the body; true when the stream was closed meanwhile. */
  async function pump(body: ReadableStream<Uint8Array>, onActivity: () => void): Promise<boolean> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (closed) {
          return true;
        }
        if (done) {
          return false;
        }
        onActivity();
        for (const event of parser.feed(decoder.decode(value, { stream: true }))) {
          if (event.id !== null) {
            lastEventId = event.id;
          }
          onEvent(event);
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  async function run(): Promise<void> {
    let failures = 0;
    let refreshedOn401 = false;
    while (!closed) {
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
          return;
        }
        if (response.status === 401) {
          if (refreshedOn401 || (await refreshAccessToken()) === null) {
            return;
          }
          refreshedOn401 = true;
          continue;
        }
        if (isFinalRefusal(response.status)) {
          return;
        }
        if (response.ok && response.body) {
          refreshedOn401 = false;
          onOpen?.();
          if (await pump(response.body, () => (failures = 0))) {
            return;
          }
        }
      } catch {
        // A failed connect or a broken body is retried below; only close() ends the loop.
        if (closed) {
          return;
        }
      }
      await sleep(backoffDelay(failures));
      failures += 1;
    }
  }

  void run();

  return {
    close() {
      closed = true;
      controller?.abort();
      if (timer !== null) {
        clearTimeout(timer);
      }
      wake?.();
    },
  };
}
