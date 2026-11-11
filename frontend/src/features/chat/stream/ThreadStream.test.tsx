import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute } from '../../../test/fakeApi';
import { ThreadPane } from '../ThreadPane';

const encoder = new TextEncoder();
const MESSAGES = 'GET /api/v1/chat/conversations/c1/messages';

interface Hub {
  push: (event: string, data: unknown) => void;
  stopRequests: string[];
  topics: string[];
  setMessages: (body: unknown[]) => void;
  messageLoads: () => number;
  /** The server ends the open stream. */
  drop: () => void;
}

/** A fetch that serves the thread and holds the hub stream open for the test to feed. */
function stubHub(initial: unknown[], streamStatus = 200): Hub {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const hub: Hub = {
    push: (event, data) =>
      controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)),
    stopRequests: [],
    topics: [],
    setMessages: (body) => {
      messages = body;
    },
    messageLoads: () => loads,
    drop: () => controller.close(),
  };
  let messages = initial;
  let loads = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const [path = '', query = ''] = input.split('?');
      const method = init?.method ?? 'GET';
      if (path === '/api/v1/realtime/stream') {
        hub.topics = new URLSearchParams(query).getAll('topic');
        if (streamStatus !== 200) return new Response(null, { status: streamStatus });
        const body = new ReadableStream<Uint8Array>({
          start(c) {
            controller = c;
          },
        });
        return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
      }
      if (`${method} ${path}` === MESSAGES) {
        loads += 1;
        return new Response(JSON.stringify(messages), { status: 200 });
      }
      if (method === 'POST' && path.endsWith('/stop')) {
        hub.stopRequests.push(path);
        return new Response(null, { status: 202 });
      }
      return new Response(JSON.stringify({ status: 404 }), { status: 404 });
    }),
  );
  return hub;
}

const USER = { id: 'u1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' };
const STREAMING = { id: 'a1', seq: 2, role: 'assistant', kind: 'text', body: '', status: 'streaming' };

function renderThread() {
  return renderRoute('/chat/:id', '/chat/c1', <ThreadPane conversationId="c1" chatEnabled />);
}

describe('ThreadPane stream', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('subscribes to the conversation topic and grows the answer from deltas, ignoring repeats', async () => {
    const hub = stubHub([USER, STREAMING]);
    renderThread();
    await waitFor(() => expect(hub.topics).toEqual(['chat:c1']));
    await screen.findByText('Writing…');

    await act(async () => {
      hub.push('chat.delta', { messageId: 'a1', offset: 0, text: 'Hello ' });
      hub.push('chat.delta', { messageId: 'a1', offset: 0, text: 'Hello ' });
      hub.push('chat.delta', { messageId: 'a1', offset: 6, text: 'world' });
    });

    expect(await screen.findByText('Hello world')).toBeInTheDocument();
  });

  it('shows the stored answer after chat.updated', async () => {
    const hub = stubHub([USER, STREAMING]);
    renderThread();
    await waitFor(() => expect(hub.topics).toEqual(['chat:c1']));
    await act(async () => {
      hub.push('chat.delta', { messageId: 'a1', offset: 0, text: 'Hel' });
    });
    await screen.findByText('Hel');

    hub.setMessages([USER, { ...STREAMING, body: 'Hello, stored.', status: 'done' }]);
    await act(async () => {
      hub.push('chat.updated', { messageId: 'a1' });
    });

    expect(await screen.findByText('Hello, stored.')).toBeInTheDocument();
    expect(screen.queryByText('Writing…')).not.toBeInTheDocument();
  });

  it('reloads the thread once when the stream reconnects and once on resync', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const hub = stubHub([USER]);
    renderThread();
    await screen.findByText('Hi');
    await waitFor(() => expect(hub.topics).toEqual(['chat:c1']));
    expect(hub.messageLoads()).toBe(1);

    await act(async () => {
      hub.drop();
    });
    await waitFor(() => expect(hub.messageLoads()).toBe(2));

    await act(async () => {
      hub.push('resync', {});
    });
    await waitFor(() => expect(hub.messageLoads()).toBe(3));
    vi.restoreAllMocks();
  });

  it('stops the answer being written and reloads the thread', async () => {
    const hub = stubHub([USER, STREAMING]);
    renderThread();
    await screen.findByText('Writing…');

    await userEvent.click(await screen.findByRole('button', { name: 'Stop' }));

    await waitFor(() => expect(hub.stopRequests).toEqual(['/api/v1/chat/conversations/c1/messages/a1/stop']));
  });

  it('reloads the thread on an interval while the stream cannot connect and an answer is still streaming', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const hub = stubHub([USER, STREAMING], 429);
      renderThread();
      await screen.findByText('Writing…');
      const loadsBefore = hub.messageLoads();

      hub.setMessages([USER, { ...STREAMING, body: 'Stored answer.', status: 'done' }]);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_100);
      });

      expect(await screen.findByText('Stored answer.')).toBeInTheDocument();
      expect(hub.messageLoads()).toBeGreaterThan(loadsBefore);
      const settledLoads = hub.messageLoads();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(20_000);
      });
      expect(hub.messageLoads()).toBe(settledLoads);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not poll while the stream is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const hub = stubHub([USER, STREAMING]);
      renderThread();
      await screen.findByText('Writing…');
      await waitFor(() => expect(hub.topics).toEqual(['chat:c1']));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
      const loads = hub.messageLoads();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(20_000);
      });

      expect(hub.messageLoads()).toBe(loads);
    } finally {
      vi.useRealTimers();
    }
  });
});
