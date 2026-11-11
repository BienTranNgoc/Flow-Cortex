import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { HomePrompt } from './HomePrompt';
import { renderRoute, settled, stubApi, type Handler } from '../../test/fakeApi';
import { ChatFeatureGate } from './ChatFeatureGate';
import { ChatPage } from './ChatPage';

const LIST = (chatEnabled = true): Handler => ({
  status: 200,
  body: {
    chatEnabled,
    items: [{ id: 'c1', title: 'Launch video', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T09:00:00Z' }],
  },
});

const MESSAGES = (body: unknown[] = []): Handler => ({ status: 200, body });

function renderChat(url = '/chat/c1') {
  return renderRoute('/chat/:conversationId', url, <ChatPage />);
}

describe('ChatPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('says the conversation no longer exists, with a link to a new chat, when it is gone', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': {
        status: 404,
        body: { status: 404, code: 'chat-conversation-not-found' },
      },
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    expect(await screen.findByText('This conversation no longer exists.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start a new chat' })).toHaveAttribute('href', '/chat');
  });

  it('keeps the history readable and replaces the composer when chat is off', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(false),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES([
        { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hello there', status: 'done' },
      ]),
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    expect(await screen.findByText('Hello there')).toBeInTheDocument();
    expect(screen.getByText('Chat is turned off for now.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('shows the quota message and the resume time under the composer and keeps the text', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES(),
      'POST /api/v1/chat/conversations/c1/messages': {
        status: 429,
        body: {
          status: 429,
          code: 'chat-quota',
          detail: "You've reached today's chat limit. You can chat again after 00:00.",
          resumeAt: '2026-11-08T17:00:00Z',
        },
      },
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    const input = await screen.findByRole('textbox', { name: 'Message' });
    await userEvent.type(input, 'Make a video{Enter}');

    expect(await screen.findByRole('alert')).toHaveTextContent(/today's chat limit/);
    expect(screen.getByRole('alert')).toHaveTextContent(/You can chat again .*00:00/);
    expect(input).toHaveValue('Make a video');
  });

  it('renders no bubble for a finished assistant turn with an empty body', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES([
        { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' },
        { id: 'm2', seq: 2, role: 'assistant', kind: 'text', body: '', status: 'done' },
      ]),
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    await screen.findByText('Hi');
    expect(screen.queryByText('Assistant')).not.toBeInTheDocument();
  });

  it('deletes after confirming and goes back to /chat when the open one is deleted', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES(),
      'DELETE /api/v1/chat/conversations/c1': { status: 204 },
    });
    HTMLDialogElement.prototype.showModal = function showModal() {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close = function close() {
      this.removeAttribute('open');
    };
    const { queryClient } = renderRoute(
      '/chat/:conversationId',
      '/chat/c1',
      <ChatPage />,
      <Route path="/chat" element={<p>chat index</p>} />,
    );
    await settled(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Conversations' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Delete: Launch video' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('chat index')).toBeInTheDocument();
  });

  it('refuses a title over 80 characters and cancels a rename on Escape', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES(),
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Conversations' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Rename: Launch video' }));
    const input = screen.getByRole('textbox', { name: 'Conversation title' });
    await userEvent.clear(input);
    await userEvent.type(input, 'x'.repeat(81));
    expect(screen.getByText('A title can have at most 80 characters.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('textbox', { name: 'Conversation title' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Launch video' })).toBeInTheDocument();
  });
});

describe('ChatPage new conversation', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('opens an empty thread with the composer focused and creates the conversation on send', async () => {
    const calls = stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'POST /api/v1/chat/conversations': {
        status: 201,
        body: { id: 'c9', title: 'Hi', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T10:00:00Z' },
      },
    });
    const { queryClient } = renderRoute(
      '/chat',
      '/chat',
      <ChatPage />,
      <Route path="/chat/:conversationId" element={<p>opened chat</p>} />,
    );
    await settled(queryClient);

    const input = await screen.findByRole('textbox', { name: 'Message' });
    expect(input).toHaveFocus();
    expect(screen.queryByText(/Pick a conversation/)).not.toBeInTheDocument();
    await userEvent.type(input, 'a product video{Enter}');

    expect(await screen.findByText('opened chat')).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      firstMessage: { text: 'a product video' },
    });
  });
});

describe('ChatPage New button', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('opens the empty thread without sending any request to create a conversation', async () => {
    const calls = stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES(),
      'POST /api/v1/chat/conversations': {
        status: 201,
        body: { id: 'c9', title: 'New chat', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T10:00:00Z' },
      },
    });
    const { queryClient } = renderRoute(
      '/chat/:conversationId',
      '/chat/c1',
      <ChatPage />,
      <Route path="/chat" element={<p>empty thread</p>} />,
    );
    await settled(queryClient);

    await userEvent.click(screen.getByRole('button', { name: 'Conversations' }));
    await userEvent.click(screen.getByRole('button', { name: 'New chat' }));

    expect(await screen.findByText('empty thread')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toEqual([]);
  });
});

describe('ChatPage failed answer', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const FAILED = MESSAGES([
    { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' },
    { id: 'm2', seq: 2, role: 'assistant', kind: 'text', body: '', status: 'failed' },
  ]);
  const RETRY_OK: Handler = {
    status: 202,
    body: {
      userMessage: { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' },
      assistantMessageId: 'm3',
    },
  };

  it('offers Try again, calls retry once and reloads the thread', async () => {
    let reads = 0;
    const calls = stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': () => {
        reads += 1;
        return FAILED as { status: number; body: unknown };
      },
      'POST /api/v1/chat/conversations/c1/messages/m2/retry': RETRY_OK,
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    expect(await screen.findByText("The assistant couldn't answer.")).toBeInTheDocument();
    const before = reads;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(reads).toBeGreaterThan(before));
    expect(calls.filter((call) => call.path.endsWith('/retry'))).toHaveLength(1);
  });

  it('disables Try again while the retry is pending', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': FAILED,
      'POST /api/v1/chat/conversations/c1/messages/m2/retry': async () => {
        await gate;
        return { status: 202, body: { userMessage: {}, assistantMessageId: 'm3' } };
      },
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled());
    release();
  });

  it('offers Try again only on the last answer of the thread', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': MESSAGES([
        { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' },
        { id: 'm2', seq: 2, role: 'assistant', kind: 'text', body: '', status: 'failed' },
        { id: 'm3', seq: 3, role: 'assistant', kind: 'text', body: '', status: 'failed' },
      ]) as { status: number; body: unknown },
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    expect(await screen.findAllByText("The assistant couldn't answer.")).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);
  });

  it('keeps every message inside a list', async () => {
    stubApi({
      'GET /api/v1/chat/conversations': LIST(),
      'GET /api/v1/chat/conversations/c1/messages': FAILED,
    });
    const { queryClient } = renderChat();
    await settled(queryClient);

    await screen.findByText('Hi');
    const parent = screen.getByText('Hi').closest('li')?.parentElement;
    expect(parent?.tagName).toBe('OL');
    expect(parent?.hasAttribute('role')).toBe(false);
    expect(screen.getByRole('log')).toContainElement(parent as HTMLElement);
  });
});

describe('ChatFeatureGate', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends the visitor home while the chat switch is off', async () => {
    stubApi({ 'GET /api/v1/features': { status: 200, body: { chat: false } } });
    renderRoute(
      '/chat',
      '/chat',
      <ChatFeatureGate>
        <p>chat shown</p>
      </ChatFeatureGate>,
      <Route path="/" element={<p>home</p>} />,
    );

    expect(await screen.findByText('home')).toBeInTheDocument();
    expect(screen.queryByText('chat shown')).not.toBeInTheDocument();
  });
});

describe('HomePrompt', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('creates a conversation with the first message and opens it', async () => {
    const calls = stubApi({
      'POST /api/v1/chat/conversations': {
        status: 201,
        body: { id: 'c9', title: 'Hi', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T10:00:00Z' },
      },
    });
    renderRoute('/', '/', <HomePrompt />, <Route path="/chat/:id" element={<p>opened chat</p>} />);

    const send = screen.getByRole('button', { name: 'Start chat' });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByRole('textbox', { name: 'What do you want to create?' }), 'a product video');
    await userEvent.click(send);

    expect(await screen.findByText('opened chat')).toBeInTheDocument();
    expect(calls[0]?.body).toEqual({ firstMessage: { text: 'a product video' } });
  });

  it('shows a refusal in place and keeps the text', async () => {
    stubApi({
      'POST /api/v1/chat/conversations': {
        status: 503,
        body: { status: 503, code: 'chat-disabled' },
      },
    });
    renderRoute('/', '/', <HomePrompt />);

    const input = screen.getByRole('textbox', { name: 'What do you want to create?' });
    await userEvent.type(input, 'a product video');
    await userEvent.click(screen.getByRole('button', { name: 'Start chat' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Chat is turned off for now.');
    expect(input).toHaveValue('a product video');
  });
});
