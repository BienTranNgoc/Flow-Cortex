import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
import {
  chatKeys,
  RECENT_FILTERS,
  useConversationList,
  useCreateConversation,
  useDeleteConversation,
  useMessages,
  useSendMessage,
} from './useConversations';

const LIST = {
  items: [
    { id: 'c2', title: 'Second', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T09:00:00Z' },
    { id: 'c1', title: 'First', lastActivityAt: '2026-11-06T10:00:00Z', createdAt: '2026-11-06T09:00:00Z' },
  ],
  nextCursor: null,
  chatEnabled: true,
};
const COUNTS = { total: 2, pinned: 0, today: 0, last7Days: 2, earlier: 0 };

const PAGES_KEY = chatKeys.page(RECENT_FILTERS);
const COUNTS_KEY = chatKeys.countsFor({ todayStart: '2026-11-07T00:00:00Z', weekStart: '2026-10-31T00:00:00Z' });

/** Both cache entries are seeded, so "stale" is the observable effect of an invalidation. */
function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(PAGES_KEY, { pages: [structuredClone(LIST)], pageParams: [undefined] });
  queryClient.setQueryData(COUNTS_KEY, COUNTS);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return { queryClient, wrapper };
}

const isStale = (queryClient: QueryClient, key: readonly unknown[]) =>
  queryClient.getQueryState(key)?.isInvalidated === true;

describe('useCreateConversation', () => {
  it('invalidates the list and count prefixes and sends the Idempotency-Key with the first message', async () => {
    const created = { id: 'c3', title: 'Hello', lastActivityAt: '2026-11-07T11:00:00Z', createdAt: '2026-11-07T11:00:00Z' };
    stubApi({ 'POST /api/v1/chat/conversations': { status: 201, body: created } });
    const fetchSpy = vi.mocked(globalThis.fetch);
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useCreateConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ firstMessage: { text: 'Hello' }, key: 'k-1' });
    });

    expect(isStale(queryClient, PAGES_KEY)).toBe(true);
    expect(isStale(queryClient, COUNTS_KEY)).toBe(true);
    const init = fetchSpy.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('k-1');
    expect(JSON.parse(init.body as string)).toEqual({ firstMessage: { text: 'Hello' } });
  });

  it('leaves the list alone when the server refuses', async () => {
    stubApi({
      'POST /api/v1/chat/conversations': { status: 429, body: { status: 429, code: 'chat-quota' } },
    });
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useCreateConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ firstMessage: { text: 'Hello' }, key: 'k-1' }).catch(() => {});
    });

    expect(isStale(queryClient, PAGES_KEY)).toBe(false);
    expect(isStale(queryClient, COUNTS_KEY)).toBe(false);
  });
});

describe('useDeleteConversation', () => {
  it('removes its cached messages and invalidates the list and count prefixes', async () => {
    stubApi({ 'DELETE /api/v1/chat/conversations/c1': { status: 204 } });
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(chatKeys.messages('c1'), []);
    const { result } = renderHook(() => useDeleteConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync('c1');
    });

    expect(queryClient.getQueryData(chatKeys.messages('c1'))).toBeUndefined();
    expect(isStale(queryClient, PAGES_KEY)).toBe(true);
    expect(isStale(queryClient, COUNTS_KEY)).toBe(true);
  });

  it('treats an already-deleted conversation as deleted', async () => {
    stubApi({
      'DELETE /api/v1/chat/conversations/c1': {
        status: 404,
        body: { status: 404, code: 'chat-conversation-not-found' },
      },
    });
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useDeleteConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync('c1').catch(() => {});
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(isStale(queryClient, PAGES_KEY)).toBe(true);
  });
});

describe('useSendMessage', () => {
  it('refetches the messages once after the 202 and refreshes the list', async () => {
    const calls = stubApi({
      'POST /api/v1/chat/conversations/c1/messages': {
        status: 202,
        body: { userMessage: { id: 'm1', seq: 1, role: 'user', kind: 'text', body: 'Hi', status: 'done' }, assistantMessageId: 'm2' },
      },
      'GET /api/v1/chat/conversations/c1/messages': { status: 200, body: [] },
      'GET /api/v1/chat/conversations': { status: 200, body: LIST },
    });
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(
      () => ({ send: useSendMessage('c1'), thread: useMessages('c1'), list: useConversationList() }),
      { wrapper },
    );
    await waitFor(() => {
      expect(result.current.thread.isSuccess).toBe(true);
      expect(result.current.list.isFetching).toBe(false);
    });
    calls.length = 0;

    await act(async () => {
      await result.current.send.mutateAsync({ text: 'Hi', key: 'k-2' });
    });

    await waitFor(() =>
      expect(calls.filter((call) => call.path === '/api/v1/chat/conversations/c1/messages' && call.method === 'GET')).toHaveLength(1),
    );
    await waitFor(() =>
      expect(calls.filter((call) => call.path === '/api/v1/chat/conversations' && call.method === 'GET')).toHaveLength(1),
    );
    expect(isStale(queryClient, COUNTS_KEY)).toBe(true);
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({ text: 'Hi' });
  });
});
