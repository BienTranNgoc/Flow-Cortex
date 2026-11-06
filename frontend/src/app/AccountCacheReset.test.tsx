import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { SupportWidget } from '../features/support/SupportWidget';
import { stubApi } from '../test/fakeApi';
import { AccountCacheReset } from './AccountCacheReset';

/** An unsigned JWT carrying `sub`; the client only reads the payload. */
function jwt(sub: string, issuedAt = 1): string {
  const part = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${part({ alg: 'none' })}.${part({ sub, iat: issuedAt })}.sig`;
}

const KEY = ['support', 'assistant', 'latest', 'member'];

function renderReset() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AccountCacheReset />
    </QueryClientProvider>,
  );
  return queryClient;
}

afterEach(() => {
  setToken(null);
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('AccountCacheReset', () => {
  it('drops every cached answer when the member signs out', () => {
    setToken(jwt('a'), true);
    const queryClient = renderReset();
    queryClient.setQueryData(KEY, { conversationId: 'c-a', messages: [] });

    act(() => setToken(null));

    expect(queryClient.getQueryData(KEY)).toBeUndefined();
  });

  it('drops the cache when another account replaces the token', () => {
    setToken(jwt('a'), true);
    const queryClient = renderReset();
    queryClient.setQueryData(KEY, { conversationId: 'c-a', messages: [] });

    act(() => setToken(jwt('b'), true));

    expect(queryClient.getQueryData(KEY)).toBeUndefined();
  });

  it('keeps the cache when a refresh rotates the token of the same account', () => {
    setToken(jwt('a', 1), true);
    const queryClient = renderReset();
    queryClient.setQueryData(KEY, { conversationId: 'c-a', messages: [] });

    act(() => setToken(jwt('a', 2), true));

    expect(queryClient.getQueryData(KEY)).toEqual({ conversationId: 'c-a', messages: [] });
  });

  it("never shows member A's conversation to member B signing in on the same tab", async () => {
    let latest: { status: number; body: unknown } = {
      status: 404,
      body: { status: 404, code: 'conversation-not-found' },
    };
    const calls = stubApi({
      'GET /api/v1/features': {
        status: 200,
        body: { supportWidget: false, supportAssistant: true },
      },
      'GET /api/v1/support/settings': { status: 200, body: { assistantAvailable: true } },
      'GET /api/v1/support/kb/featured': { status: 200, body: [] },
      'GET /api/v1/support/assistant/conversations/latest': () => latest,
      'POST /api/v1/support/assistant/messages': {
        status: 200,
        body: {
          conversationId: 'c-a',
          message: {
            role: 'assistant',
            text: 'Trả lời riêng cho A',
            outcome: 'answered',
            sources: [],
          },
          quota: { remaining: 9, resetsAt: '2026-10-23T17:00:00Z' },
        },
      },
    });
    setToken(jwt('a'), true);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <AccountCacheReset />
        <MemoryRouter initialEntries={['/workflows']}>
          <SupportWidget />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Help' }));
    const panel = screen.getByRole('dialog', { name: 'Help Center' });
    await user.click(within(panel).getByRole('tab', { name: 'Assistant' }));
    const input = await within(panel).findByRole('textbox', { name: 'Your question' });
    await waitFor(() => expect(input).toBeEnabled());
    await user.type(input, 'Câu hỏi của A');
    await user.click(within(panel).getByRole('button', { name: 'Send' }));
    expect(await within(panel).findByText('Trả lời riêng cho A')).toBeInTheDocument();

    latest = { status: 404, body: { status: 404, code: 'conversation-not-found' } };
    const readsBefore = calls.filter((call) => call.path.endsWith('/conversations/latest')).length;
    act(() => setToken(null));
    act(() => setToken(jwt('b'), true));

    await waitFor(() =>
      expect(
        calls.filter((call) => call.path.endsWith('/conversations/latest')).length,
      ).toBeGreaterThan(readsBefore),
    );
    await within(panel).findByText(/Ask a question/);
    expect(screen.queryByText('Trả lời riêng cho A')).toBeNull();
    expect(screen.queryByText('Câu hỏi của A')).toBeNull();
  });
});
