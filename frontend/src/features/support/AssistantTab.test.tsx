import { setToken, useAuthToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useMemo, useReducer } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../../lib/i18n';
import { stubApi, type FakeReply, type Handler } from '../../test/fakeApi';
import { AssistantTab } from './AssistantTab';
import { RequestsTab } from './RequestsTab';
import {
  CLOSED_PANEL,
  SupportPanelContext,
  supportPanelReducer,
  type SupportPanelState,
} from './supportPanelState';

const MEMBER_ASK = 'POST /api/v1/support/assistant/messages';
const GUEST_ASK = 'POST /api/v1/support/assistant/guest/messages';
const MEMBER_LATEST = 'GET /api/v1/support/assistant/conversations/latest';
const GUEST_LATEST = 'GET /api/v1/support/assistant/guest/conversations/latest';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const NOT_FOUND = {
  status: 404,
  body: { status: 404, code: 'conversation-not-found', detail: 'No conversation' },
};

function features(supportAssistant = true): Handler {
  return {
    status: 200,
    body: {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
      supportWidget: true,
      supportAssistant,
    },
  };
}

function settings(contactEmail?: string): Handler {
  return {
    status: 200,
    body: contactEmail ? { contactEmail, assistantAvailable: true } : { assistantAvailable: true },
  };
}

function reply(
  outcome: 'answered' | 'no_answer' | 'blocked',
  text = 'Vào **Hồ sơ > Credit** rồi chọn gói.',
  sources = [{ slug: 'nap-credit', title: 'Nạp credit' }],
  conversationId = 'c-1',
): FakeReply {
  return {
    status: 200,
    body: {
      conversationId,
      message: { role: 'assistant', text, outcome, sources },
      quota: { remaining: 9, resetsAt: '2026-10-23T17:00:00Z' },
    },
  };
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): FakeReply {
  return { status, body: { status, code, detail: code, ...extra } };
}

function api(routes: Record<string, Handler>, contactEmail?: string) {
  return stubApi({
    'GET /api/v1/features': features(),
    'GET /api/v1/support/settings': settings(contactEmail),
    [MEMBER_LATEST]: NOT_FOUND,
    [GUEST_LATEST]: NOT_FOUND,
    ...routes,
  });
}

function Panel({ initial }: Readonly<{ initial: SupportPanelState }>) {
  const member = useAuthToken() !== null;
  const [state, dispatch] = useReducer(supportPanelReducer, initial);
  const context = useMemo(() => ({ state, dispatch, member }), [state, member]);
  let body = <AssistantTab />;
  if (state.article) body = <p>Bài viết: {state.article}</p>;
  else if (state.tab === 'requests') body = <RequestsTab />;
  else if (state.tab !== 'assistant') {
    body = <p>Tab: {state.newRequest ? 'yêu cầu mới' : state.tab}</p>;
  }
  return <SupportPanelContext.Provider value={context}>{body}</SupportPanelContext.Provider>;
}

function renderTab(question: string | null = null) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const initial = { ...CLOSED_PANEL, open: true, tab: 'assistant' as const, question };
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workflows']}>
        <Routes>
          <Route path="/workflows" element={<Panel initial={initial} />} />
          <Route path="/login" element={<p>Trang đăng nhập</p>} />
          <Route path="/register" element={<p>Trang đăng ký</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup() };
}

async function ask(user: ReturnType<typeof userEvent.setup>, question: string) {
  const input = await screen.findByRole('textbox', { name: 'Your question' });
  await waitFor(() => expect(input).toBeEnabled());
  await user.type(input, question);
  await user.click(screen.getByRole('button', { name: 'Send' }));
}

const CREATED = {
  number: 13,
  type: 'credit',
  subject: 'Cần hỗ trợ thêm',
  status: 'new',
  memberUnread: false,
  createdAt: '2026-10-19T08:00:00Z',
  lastActivityAt: '2026-10-19T08:00:00Z',
  messages: [],
};

const CREATE: Record<string, Handler> = {
  'POST /api/v1/support/requests': { status: 201, body: CREATED },
  'GET /api/v1/support/requests/13': { status: 200, body: CREATED },
};

/** Files the request the offer opened and returns what the create call linked. */
async function fileLinkedRequest(
  user: ReturnType<typeof userEvent.setup>,
  calls: { method: string; path: string; body: unknown }[],
) {
  const description = await screen.findByLabelText('Description');
  const prefilled = (description as HTMLTextAreaElement).value;
  await user.selectOptions(screen.getByLabelText('Request type'), 'Credit');
  await user.type(screen.getByLabelText('Subject'), 'Cần hỗ trợ thêm');
  await user.type(description, ' Mình đã thử lại.');
  await user.click(screen.getByRole('button', { name: 'Send request' }));
  await waitFor(() =>
    expect(
      calls.some((call) => call.method === 'POST' && call.path === '/api/v1/support/requests'),
    ).toBe(true),
  );
  const created = calls.find(
    (call) => call.method === 'POST' && call.path === '/api/v1/support/requests',
  )?.body as { description: string; aiConversationId?: string };
  expect(created.description.startsWith(prefilled)).toBe(true);
  return { description: prefilled, aiConversationId: created.aiConversationId };
}

function sentQuestions(calls: { method: string; path: string; body: unknown }[], route: string) {
  const [method, path] = route.split(' ');
  return calls
    .filter((call) => call.method === method && call.path === path)
    .map((call) => call.body as { question: string; conversationId?: string });
}

afterEach(() => {
  setToken(null);
  vi.restoreAllMocks();
  window.localStorage.clear();
  vi.unstubAllGlobals();
});

describe('AssistantTab', () => {
  it('answers with its source, which opens the article in the panel', async () => {
    setToken('member', true);
    const calls = api({ [MEMBER_ASK]: reply('answered') });
    const { user } = renderTab();

    await ask(user, 'Làm sao nạp credit?');

    const log = screen.getByRole('log');
    expect(await within(log).findByText('Hồ sơ > Credit')).toBeInTheDocument();
    expect(within(log).getByText('Làm sao nạp credit?')).toBeInTheDocument();
    expect(sentQuestions(calls, MEMBER_ASK)).toEqual([{ question: 'Làm sao nạp credit?' }]);

    await user.click(within(log).getByRole('button', { name: 'Nạp credit' }));

    expect(screen.getByText('Bài viết: nap-credit')).toBeInTheDocument();
  });

  it('offers a member without an answer "Gửi yêu cầu hỗ trợ" opening a new request', async () => {
    setToken('member', true);
    const calls = api({ [MEMBER_ASK]: reply('no_answer', 'ignored', []), ...CREATE });
    const { user } = renderTab();

    await ask(user, 'Hoàn tiền thế nào?');

    const log = screen.getByRole('log');
    expect(
      await within(log).findByText("I don't have an answer to this question yet"),
    ).toBeInTheDocument();
    expect(within(log).queryByText('ignored')).toBeNull();
    expect(within(log).queryByRole('link', { name: 'Sign in' })).toBeNull();

    await user.click(within(log).getByRole('button', { name: 'Send a support request' }));

    expect(await fileLinkedRequest(user, calls)).toEqual({
      description: 'Hoàn tiền thế nào?',
      aiConversationId: 'c-1',
    });
  });

  it('offers a guest without an answer sign-in, registration and the contact, never a request', async () => {
    const calls = api({ [GUEST_ASK]: reply('no_answer', '', []) }, 'hotro@nodi.vn');
    const { user } = renderTab();

    await ask(user, 'Hoàn tiền thế nào?');

    const log = screen.getByRole('log');
    await within(log).findByText("I don't have an answer to this question yet");
    expect(within(log).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
    expect(within(log).getByRole('link', { name: 'Create account' })).toHaveAttribute(
      'href',
      '/register',
    );
    expect(
      await within(log).findByRole('link', { name: 'Email hotro@nodi.vn' }),
    ).toHaveAttribute('href', 'mailto:hotro@nodi.vn');
    expect(within(log).queryByRole('button', { name: /Send a support request/ })).toBeNull();

    const session = window.localStorage.getItem('nodi.support.session');
    expect(session).toMatch(UUID_V4);
    const fetchCalls = vi.mocked(fetch).mock.calls as [string, RequestInit][];
    const guestAsk = fetchCalls.find(([url]) => url === '/api/v1/support/assistant/guest/messages');
    expect(new Headers(guestAsk?.[1].headers).get('X-Support-Session')).toBe(session);
    expect(sentQuestions(calls, MEMBER_ASK)).toEqual([]);
  });

  it('puts the contact line under an answered guest reply', async () => {
    api({ [GUEST_ASK]: reply('answered', 'Đặt lại mật khẩu ở trang đăng nhập.') }, 'hotro@nodi.vn');
    const { user } = renderTab();

    await ask(user, 'Không đăng nhập được?');

    const log = screen.getByRole('log');
    await within(log).findByText('Đặt lại mật khẩu ở trang đăng nhập.');
    expect(
      await within(log).findByRole('link', { name: 'Email hotro@nodi.vn' }),
    ).toBeInTheDocument();
    expect(within(log).queryByRole('button', { name: /Send a support request/ })).toBeNull();
  });

  it('shows no contact line under a guest reply when no contact is configured', async () => {
    api({ [GUEST_ASK]: reply('no_answer', '', []) });
    const { user } = renderTab();

    await ask(user, 'Hoàn tiền thế nào?');

    const log = screen.getByRole('log');
    await within(log).findByText("I don't have an answer to this question yet");
    expect(within(log).queryByText(/Email /)).toBeNull();
    expect(within(log).queryByText('Need help from a person?')).toBeNull();
  });

  it('offers "Vẫn chưa giải quyết? Gửi yêu cầu hỗ trợ" under a member answer, opening a new request', async () => {
    setToken('member', true);
    const calls = api({
      [MEMBER_ASK]: reply('answered', 'Kiểm tra lịch sử lượt chạy.'),
      ...CREATE,
    });
    const { user } = renderTab();

    await ask(user, 'workflow bị lỗi mất credit');

    const log = screen.getByRole('log');
    await within(log).findByText('Kiểm tra lịch sử lượt chạy.');
    await user.click(
      within(log).getByRole('button', { name: 'Still not solved? Send a support request' }),
    );

    expect(await fileLinkedRequest(user, calls)).toEqual({
      description: 'workflow bị lỗi mất credit',
      aiConversationId: 'c-1',
    });
  });

  it('says "asking a bit fast" when the per-minute cap lifts within the next minutes', async () => {
    setToken('member', true);
    const soon = new Date(Date.now() + 20_000).toISOString();
    api({ [MEMBER_ASK]: problem(429, 'assistant-quota', { retryAt: soon }) });
    const { user } = renderTab();

    await ask(user, 'Câu hỏi thứ mười một trong phút');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/^You're asking a bit fast, try again after \d{2}:\d{2}\.$/);
    expect(alert).not.toHaveTextContent('today');
  });

  it('shows the unavailable notice and locks the input when the assistant is paused', async () => {
    setToken('member', true);
    api({
      [MEMBER_ASK]: problem(503, 'assistant-paused', { retryAt: '2026-10-23T17:00:00Z' }),
    });
    const { user } = renderTab();

    await ask(user, 'Làm sao nạp credit?');

    expect(await screen.findByText('The assistant is temporarily unavailable')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Your question' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('tells a guest over quota when questions resume and offers sign-in for more', async () => {
    api({ [GUEST_ASK]: problem(429, 'assistant-quota', { retryAt: '2026-10-23T17:00:00Z' }) });
    const { user } = renderTab();

    await ask(user, 'Câu hỏi thứ mười một');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      "You've used all your questions for today. You can ask again after 00:00.",
    );
    expect(within(alert).getByRole('link', { name: 'Sign in for more questions' })).toHaveAttribute(
      'href',
      '/login',
    );
  });

  it('keeps the question when the assistant is busy and "Try again" sends it again', async () => {
    setToken('member', true);
    let attempt = 0;
    const calls = api({
      [MEMBER_ASK]: () => {
        attempt += 1;
        return attempt === 1 ? problem(503, 'assistant-unavailable') : reply('answered', 'Đã rõ.');
      },
    });
    const { user } = renderTab();

    await ask(user, 'Làm sao nạp credit?');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The assistant is busy, please try again in a few minutes');
    expect(within(screen.getByRole('log')).getByText('Làm sao nạp credit?')).toBeInTheDocument();

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await within(screen.getByRole('log')).findByText('Đã rõ.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(sentQuestions(calls, MEMBER_ASK).map((body) => body.question)).toEqual([
      'Làm sao nạp credit?',
      'Làm sao nạp credit?',
    ]);
  });

  it('shows the fixed refusal for a blocked answer, never the provider text', async () => {
    setToken('member', true);
    api({ [MEMBER_ASK]: reply('blocked', 'provider text', []) });
    const { user } = renderTab();

    await ask(user, 'Câu hỏi bị chặn');

    const log = screen.getByRole('log');
    expect(await within(log).findByText("I can't answer this question")).toBeInTheDocument();
    expect(within(log).queryByText('provider text')).toBeNull();
    expect(within(log).getByRole('button', { name: 'Send a support request' })).toBeInTheDocument();
  });

  it('shows the saved conversation with its sources, and an old reply without any', async () => {
    setToken('member', true);
    api({
      [MEMBER_LATEST]: {
        status: 200,
        body: {
          conversationId: 'c-old',
          messages: [
            { role: 'user', text: 'Hôm qua hỏi', sources: [], createdAt: '2026-10-22T03:00:00Z' },
            {
              role: 'assistant',
              text: 'Câu trả lời hôm qua',
              outcome: 'answered',
              sources: [{ slug: 'bai-cu', title: 'Bài đã lưu trữ' }],
              createdAt: '2026-10-22T03:00:01Z',
            },
            {
              role: 'assistant',
              text: 'Câu trả lời cũ không có nguồn',
              createdAt: '2026-10-22T03:00:02Z',
            },
          ],
        },
      },
    });
    const { user } = renderTab();

    const log = screen.getByRole('log');
    expect(await within(log).findByText('Câu trả lời hôm qua')).toBeInTheDocument();
    expect(within(log).getByText('Câu trả lời cũ không có nguồn')).toBeInTheDocument();
    await user.click(within(log).getByRole('button', { name: 'Bài đã lưu trữ' }));
    expect(screen.getByText('Bài viết: bai-cu')).toBeInTheDocument();
  });

  it('continues the previous conversation until "New conversation" starts over', async () => {
    setToken('member', true);
    const calls = api({
      [MEMBER_LATEST]: {
        status: 200,
        body: {
          conversationId: 'c-old',
          messages: [
            { role: 'user', text: 'Hôm qua hỏi', sources: [], createdAt: '2026-10-22T03:00:00Z' },
          ],
        },
      },
      [MEMBER_ASK]: reply('answered', 'Trả lời mới', [], 'c-old'),
    });
    const { user } = renderTab();

    await ask(user, 'Hỏi tiếp');
    expect(await screen.findByText('Trả lời mới')).toBeInTheDocument();
    expect(screen.getByText('Hôm qua hỏi')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'New conversation' }));
    expect(screen.queryByText('Hôm qua hỏi')).toBeNull();
    await ask(user, 'Câu mới');

    await waitFor(() => expect(sentQuestions(calls, MEMBER_ASK)).toHaveLength(2));
    expect(sentQuestions(calls, MEMBER_ASK)).toEqual([
      { question: 'Hỏi tiếp', conversationId: 'c-old' },
      { question: 'Câu mới' },
    ]);
  });

  it('shows the notice and a link to Guides when the assistant switch is off', async () => {
    setToken('member', true);
    const calls = api({ 'GET /api/v1/features': features(false) });
    const { user } = renderTab();

    expect(await screen.findByText('The assistant is temporarily unavailable')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Your question' })).toBeDisabled();
    expect(sentQuestions(calls, MEMBER_ASK)).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'View Guides' }));

    expect(screen.getByText('Tab: guides')).toBeInTheDocument();
  });

  it('gives a guest the notice with a sign-in link when the assistant switch is off', async () => {
    api({ 'GET /api/v1/features': features(false) });
    renderTab();

    expect(await screen.findByText('The assistant is temporarily unavailable')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
  });

  it('sends a handed-over question once, as the first user message', async () => {
    setToken('member', true);
    const calls = api({ [MEMBER_ASK]: reply('answered', 'Vào Hồ sơ.') });
    renderTab('Làm sao nạp credit?');

    const log = screen.getByRole('log');
    expect(await within(log).findByText('Vào Hồ sơ.')).toBeInTheDocument();
    expect(within(log).getByText('Làm sao nạp credit?')).toBeInTheDocument();
    expect(sentQuestions(calls, MEMBER_ASK)).toEqual([{ question: 'Làm sao nạp credit?' }]);
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('');
  });

  it('still asks as a guest when storage is blocked, keeping one session id', async () => {
    const blocked = () => {
      throw new DOMException('blocked', 'SecurityError');
    };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked);
    api({ [GUEST_ASK]: reply('answered', 'Vẫn trả lời.') });
    const { user } = renderTab();

    await ask(user, 'Câu hỏi');

    expect(await screen.findByText('Vẫn trả lời.')).toBeInTheDocument();
    const sessions = (vi.mocked(fetch).mock.calls as [string, RequestInit][])
      .filter(([url]) => url.includes('/assistant/guest/'))
      .map(([, init]) => new Headers(init.headers).get('X-Support-Session'));
    expect(sessions).toHaveLength(2);
    expect(sessions[0]).toMatch(UUID_V4);
    expect(sessions[1]).toBe(sessions[0]);
  });

  it('keeps focus in the question box after sending with the button and after "Try again"', async () => {
    setToken('member', true);
    let answer: FakeReply = problem(503, 'assistant-unavailable');
    api({ [MEMBER_ASK]: () => answer });
    const { user } = renderTab();

    await ask(user, 'Làm sao nạp credit?');
    const input = screen.getByRole('textbox', { name: 'Your question' });
    expect(input).toHaveFocus();

    answer = reply('answered', 'Vào Hồ sơ.');
    await user.click(await screen.findByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Vào Hồ sơ.')).toBeInTheDocument();
    expect(input).toHaveFocus();
  });

  it('puts the question back in the box on the per-minute cap, without an unsent bubble', async () => {
    setToken('member', true);
    api({
      [MEMBER_ASK]: problem(429, 'assistant-quota', { retryAt: '2030-01-01T05:00:20Z' }),
    });
    const { user } = renderTab();

    await ask(user, 'Hỏi nhanh quá');

    expect(await screen.findByText(/You're asking a bit fast/)).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('Hỏi nhanh quá');
    expect(within(screen.getByRole('log')).queryByText('Hỏi nhanh quá')).toBeNull();
  });

  it('shows a question that is too long once, back in the box', async () => {
    setToken('member', true);
    api({ [MEMBER_ASK]: problem(400, 'question-too-long') });
    const { user } = renderTab();

    await ask(user, 'Câu hỏi bị từ chối');

    expect(await screen.findByText('Questions can be at most 1000 characters')).toBeInTheDocument();
    expect(within(screen.getByRole('log')).queryByText('Câu hỏi bị từ chối')).toBeNull();
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue(
      'Câu hỏi bị từ chối',
    );
  });

  it('tells a suspended member so when the history is refused, with no retry', async () => {
    setToken('member', true);
    api({ [MEMBER_LATEST]: problem(403, 'account-suspended') }, 'hotro@nodi.vn');
    renderTab();

    expect(await screen.findByText('Your account is suspended')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    expect(screen.getByRole('textbox', { name: 'Your question' })).toBeDisabled();
  });

  it('tells a suspended member so when a question is refused, with no retry', async () => {
    setToken('member', true);
    api({ [MEMBER_ASK]: problem(403, 'account-suspended') });
    const { user } = renderTab();

    await ask(user, 'Làm sao nạp credit?');

    expect(await screen.findByText('Your account is suspended')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });

  it('reads the quota message and the question box in Vietnamese once the language is switched', async () => {
    await i18n.changeLanguage('vi');
    try {
      api({ [GUEST_ASK]: problem(429, 'assistant-quota', { retryAt: '2026-10-23T17:00:00Z' }) });
      const { user } = renderTab();
      const input = await screen.findByRole('textbox', { name: 'Câu hỏi của bạn' });
      await waitFor(() => expect(input).toBeEnabled());
      await user.type(input, 'Câu hỏi thứ mười một');
      await user.click(screen.getByRole('button', { name: 'Gửi' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Bạn đã dùng hết lượt hỏi hôm nay. Bạn có thể hỏi tiếp sau 00:00.',
      );
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('counts code points, disabling send when empty or over 1000', async () => {
    setToken('member', true);
    api({});
    const { user } = renderTab();

    const input = await screen.findByRole('textbox', { name: 'Your question' });
    await waitFor(() => expect(input).toBeEnabled());
    const send = screen.getByRole('button', { name: 'Send' });
    expect(send).toBeDisabled();
    expect(screen.getByText('0/1000')).toBeInTheDocument();

    await user.click(input);
    await user.paste('a'.repeat(999) + '😀');
    expect(screen.getByText('1000/1000')).toBeInTheDocument();
    expect(send).toBeEnabled();

    await user.paste('b');
    expect(screen.getByText('1001/1000')).toBeInTheDocument();
    expect(send).toBeDisabled();
  });
});
