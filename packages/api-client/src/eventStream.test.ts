import { openEventStream, type StreamEvent } from './eventStream';
import { setToken } from './tokenStore';

const encoder = new TextEncoder();

/** A response whose body the test feeds by hand; `end()` is the server completing the stream. */
function liveStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    response: new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    push: (text: string) => controller.enqueue(encoder.encode(text)),
    end: () => controller.close(),
  };
}

function problem(status: number) {
  return new Response(JSON.stringify({ status }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

type FetchStub = ReturnType<typeof vi.fn<[string, RequestInit?], Promise<Response>>>;

function requestOf(fetchStub: FetchStub, call: number) {
  const [url, init] = fetchStub.mock.calls[call] as [string, RequestInit];
  return { url, headers: new Headers(init.headers), signal: init.signal };
}

function callsTo(fetchStub: FetchStub, match: (url: string) => boolean) {
  return fetchStub.mock.calls.filter(([url]) => match(String(url)));
}

const settle =() => vi.advanceTimersByTimeAsync(0);

describe('openEventStream', () => {
  let events: StreamEvent[];
  let onOpen: ReturnType<typeof vi.fn>;
  let fetchStub: FetchStub;
  const streamCalls = () => callsTo(fetchStub, (url) => url.includes('/realtime/'));
  const refreshCalls = () => callsTo(fetchStub, (url) => url === '/api/v1/auth/refresh');

  /** Stream requests take the scripted responses in order; a refresh call takes `refreshReply`. */
  function open(
    responses: Array<Response | (() => Response)>,
    refreshReply: () => Response = () => problem(401),
  ) {
    const queue = [...responses];
    fetchStub = vi.fn(async (url: string) => {
      if (url === '/api/v1/auth/refresh') {
        return refreshReply();
      }
      const next = queue.shift();
      if (next === undefined) {
        throw new TypeError('no more scripted responses');
      }
      return typeof next === 'function' ? next() : next;
    });
    vi.stubGlobal('fetch', fetchStub);
    return openEventStream({
      topics: ['run:r1'],
      onEvent: (event) => events.push(event),
      onOpen,
    });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    // Full jitter at its upper bound makes every delay equal to its cap.
    vi.spyOn(Math, 'random').mockReturnValue(1);
    setToken('t1', false);
    events = [];
    onOpen = vi.fn();
  });

  afterEach(() => {
    setToken(null);
    window.sessionStorage.clear();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('asks for the topics with the bearer token and reports the open', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();

    const { url, headers } = requestOf(fetchStub, 0);
    expect(url).toBe('/api/v1/realtime/stream?topic=run%3Ar1');
    expect(headers.get('Authorization')).toBe('Bearer t1');
    expect(headers.get('Accept')).toBe('text/event-stream');
    expect(headers.has('Last-Event-ID')).toBe(false);
    expect(onOpen).toHaveBeenCalledTimes(1);
    handle.close();
  });

  it('parses a frame split across chunks', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();

    stream.push('id: a:1\nevent: run.upd');
    await settle();
    expect(events).toEqual([]);
    stream.push('ated\ndata: {"runId":"r1"}\n');
    await settle();
    expect(events).toEqual([]);
    stream.push('\n');
    await settle();

    expect(events).toEqual([{ id: 'a:1', event: 'run.updated', data: '{"runId":"r1"}' }]);
    handle.close();
  });

  it('ignores comment lines such as the heartbeat', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();

    stream.push(': ping\n\n: ping\nevent: resync\ndata: {}\n\n');
    await settle();

    expect(events).toEqual([{ id: null, event: 'resync', data: '{}' }]);
    handle.close();
  });

  it('joins multi-line data with newlines', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();

    stream.push('event: x\ndata: one\ndata: two\ndata:three\n\n');
    await settle();

    expect(events).toEqual([{ id: null, event: 'x', data: 'one\ntwo\nthree' }]);
    handle.close();
  });

  it('handles CRLF line endings, also when the pair is split between chunks', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();

    stream.push('event: x\r\ndata: one\r');
    await settle();
    stream.push('\n\r\n');
    await settle();

    expect(events).toEqual([{ id: null, event: 'x', data: 'one' }]);
    handle.close();
  });

  it('remembers the event id and sends it as Last-Event-ID after the stream ends', async () => {
    const first = liveStream();
    const second = liveStream();
    const handle = open([first.response, second.response]);
    await settle();
    first.push('id: a:7\nevent: run.updated\ndata: {}\n\n');
    await settle();
    first.end();
    await settle();

    await vi.advanceTimersByTimeAsync(1000);

    expect(fetchStub).toHaveBeenCalledTimes(2);
    expect(requestOf(fetchStub, 1).headers.get('Last-Event-ID')).toBe('a:7');
    expect(onOpen).toHaveBeenCalledTimes(2);
    handle.close();
  });

  it('reconnects with backoff after a clean end of stream, not before', async () => {
    const first = liveStream();
    const second = liveStream();
    const handle = open([first.response, second.response]);
    await settle();
    first.push(': ping\n\n');
    await settle();
    first.end();
    await settle();

    await vi.advanceTimersByTimeAsync(999);
    expect(fetchStub).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchStub).toHaveBeenCalledTimes(2);
    handle.close();
  });

  it('refreshes the token once on a 401 and retries once with the new token', async () => {
    const stream = liveStream();
    const handle = open([problem(401), stream.response], () => {
      setToken('t2', false);
      return new Response(JSON.stringify({ accessToken: 't2' }), { status: 200 });
    });
    await settle();
    await settle();

    expect(streamCalls()).toHaveLength(2);
    expect(refreshCalls()).toHaveLength(1);
    expect(requestOf(fetchStub, fetchStub.mock.calls.length - 1).headers.get('Authorization')).toBe(
      'Bearer t2',
    );
    expect(onOpen).toHaveBeenCalledTimes(1);
    handle.close();
  });

  it('stops after a second 401', async () => {
    const handle = open([problem(401), problem(401), problem(401)], () => {
      setToken('t2', false);
      return new Response(JSON.stringify({ accessToken: 't2' }), { status: 200 });
    });
    await settle();
    await settle();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(streamCalls()).toHaveLength(2);
    expect(refreshCalls()).toHaveLength(1);
    expect(onOpen).not.toHaveBeenCalled();
    handle.close();
  });

  it('stops when the refresh fails', async () => {
    const handle = open([problem(401), problem(401)]);
    await settle();
    await settle();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(streamCalls()).toHaveLength(1);
    expect(refreshCalls()).toHaveLength(1);
    handle.close();
  });

  it.each([400, 403, 404])('does not retry a %i', async (status) => {
    const handle = open([problem(status)]);
    await settle();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(fetchStub).toHaveBeenCalledTimes(1);
    expect(onOpen).not.toHaveBeenCalled();
    handle.close();
  });

  it.each([408, 429, 503])('retries a %i after the backoff', async (status) => {
    const stream = liveStream();
    const handle = open([problem(status), stream.response]);
    await settle();
    expect(fetchStub).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(fetchStub).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(fetchStub).toHaveBeenCalledTimes(2);
    expect(onOpen).toHaveBeenCalledTimes(1);
    handle.close();
  });

  it('doubles the delay on consecutive failures up to 30 seconds', async () => {
    const handle = open([problem(503), problem(503), problem(503), problem(503), problem(503)]);
    fetchStub.mockImplementation(async () => problem(503));
    await settle();

    for (const delay of [1000, 2000, 4000, 8000, 16_000, 30_000, 30_000]) {
      const before = fetchStub.mock.calls.length;
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(fetchStub.mock.calls.length).toBe(before);
      await vi.advanceTimersByTimeAsync(1);
      expect(fetchStub.mock.calls.length).toBe(before + 1);
    }
    handle.close();
  });

  it('retries after a network error', async () => {
    const stream = liveStream();
    const handle = open([
      () => {
        throw new TypeError('network down');
      },
      stream.response,
    ]);
    await settle();
    await vi.advanceTimersByTimeAsync(1000);

    expect(fetchStub).toHaveBeenCalledTimes(2);
    handle.close();
  });

  it('ends the loop on close: aborts the request and never reconnects', async () => {
    const stream = liveStream();
    const handle = open([stream.response]);
    await settle();
    const { signal } = requestOf(fetchStub, 0);

    handle.close();
    await settle();
    stream.push('event: x\ndata: late\n\n');
    await vi.advanceTimersByTimeAsync(60_000);

    expect(signal?.aborted).toBe(true);
    expect(fetchStub).toHaveBeenCalledTimes(1);
    expect(events).toEqual([]);
  });

  describe('idle watchdog', () => {
    it('drops a connection that stays silent for 45 seconds and reconnects with the last event id', async () => {
      const first = liveStream();
      const second = liveStream();
      const handle = open([first.response, second.response]);
      await settle();
      first.push('id: a:1\nevent: run.updated\ndata: {}\n\n');
      await settle();
      const { signal } = requestOf(fetchStub, 0);

      await vi.advanceTimersByTimeAsync(44_999);
      expect(signal?.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      expect(signal?.aborted).toBe(true);

      await vi.advanceTimersByTimeAsync(1000);
      expect(fetchStub).toHaveBeenCalledTimes(2);
      expect(requestOf(fetchStub, 1).headers.get('Last-Event-ID')).toBe('a:1');
      expect(onOpen).toHaveBeenCalledTimes(2);
      handle.close();
    });

    it('re-arms on every chunk, a heartbeat comment included', async () => {
      const stream = liveStream();
      const handle = open([stream.response]);
      await settle();
      const { signal } = requestOf(fetchStub, 0);

      await vi.advanceTimersByTimeAsync(30_000);
      stream.push(': ping\n\n');
      await vi.advanceTimersByTimeAsync(30_000);
      expect(signal?.aborted).toBe(false);

      await vi.advanceTimersByTimeAsync(15_000);
      expect(signal?.aborted).toBe(true);
      handle.close();
    });

    it('honours a custom idle timeout', async () => {
      const stream = liveStream();
      fetchStub = vi.fn(async (_url: string) => stream.response);
      vi.stubGlobal('fetch', fetchStub);
      const handle = openEventStream({ topics: ['run:r1'], onEvent: () => undefined, idleTimeoutMs: 5000 });
      await settle();
      const { signal } = requestOf(fetchStub, 0);

      await vi.advanceTimersByTimeAsync(5000);

      expect(signal?.aborted).toBe(true);
      handle.close();
    });

    it('leaves no timer running after close', async () => {
      const stream = liveStream();
      const handle = open([stream.response]);
      await settle();

      handle.close();
      await settle();

      expect(vi.getTimerCount()).toBe(0);
    });
  });

  it('ends the loop on close while waiting to reconnect', async () => {
    const handle = open([problem(503)]);
    await settle();

    handle.close();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(fetchStub).toHaveBeenCalledTimes(1);
  });
});
