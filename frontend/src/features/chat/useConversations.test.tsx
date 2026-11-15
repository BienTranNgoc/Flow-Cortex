import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
import {
  chatKeys,
  useCreateConversation,
  useDeleteConversation,
  useMessages,
  useRenameConversation,
  useSendMessage,
} from './useConversations';

const LIST = {
  items: [
    { id: 'c2', title: 'Second', lastActivityAt: '2026-11-07T10:00:00Z', createdAt: '2026-11-07T09:00:00Z' },
    { id: 'c1', title: 'First', lastActivityAt: '2026-11-06T10:00:00Z', createdAt: '2026-11-06T09:00:00Z' },
  ],
  chatEnabled: true,
};

function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(chatKeys.list, structuredClone(LIST));
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return { queryClient, wrapper };
}

const listItems = (queryClient: QueryClient) =>
  (queryClient.getQueryData(chatKeys.list) as typeof LIST).items;

describe('useCreateConversation', () => {
  it('puts the new conversation first and sends the Idempotency-Key with the first message', async () => {
    const created = { id: 'c3', title: 'Hello', lastActivityAt: '2026-11-07T11:00:00Z', createdAt: '2026-11-07T11:00:00Z' };
    const headers: Headers[] = [];
    stubApi({ 'POST /api/v1/chat/conversations': { status: 201, body: created } });
    const fetchSpy = vi.mocked(globalThis.fetch);
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useCreateConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ firstMessage: { text: 'Hello' }, key: 'k-1' });
    });

    expect(listItems(queryClient).map((item) => item.id)).toEqual(['c3', 'c2', 'c1']);
    const init = fetchSpy.mock.calls[0]?.[1] as RequestInit;
    headers.push(new Headers(init.headers));
    expect(headers[0]?.get('Idempotency-Key')).toBe('k-1');
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

    expect(listItems(queryClient).map((item) => item.id)).toEqual(['c2', 'c1']);
  });
});

describe('useRenameConversation', () => {
  it('replaces the title in the cached list without a refetch', async () => {
    const calls = stubApi({
      'PUT /api/v1/chat/conversations/c1': { status: 200, body: { ...LIST.items[1], title: 'Renamed' } },
    });
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useRenameConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ id: 'c1', title: 'Renamed' });
    });

    expect(listItems(queryClient).find((item) => item.id === 'c1')?.title).toBe('Renamed');
    expect(calls.filter((call) => call.method === 'GET')).toEqual([]);
    expect(calls[0]?.body).toEqual({ title: 'Renamed' });
  });
});

describe('useDeleteConversation', () => {
  it('removes the conversation and its cached messages', async () => {
    stubApi({ 'DELETE /api/v1/chat/conversations/c1': { status: 204 } });
    const { queryClient, wrapper } = setup();
    queryClient.setQueryData(chatKeys.messages('c1'), []);
    const { result } = renderHook(() => useDeleteConversation(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync('c1');
    });

    expect(listItems(queryClient).map((item) => item.id)).toEqual(['c2']);
    expect(queryClient.getQueryData(chatKeys.messages('c1'))).toBeUndefined();
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

    expect(listItems(queryClient).map((item) => item.id)).toEqual(['c2']);
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
    const { wrapper } = setup();
    const { result } = renderHook(
      () => ({ send: useSendMessage('c1'), thread: useMessages('c1') }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.thread.isSuccess).toBe(true));
    calls.length = 0;

    await act(async () => {
      await result.current.send.mutateAsync({ text: 'Hi', key: 'k-2' });
    });

    await waitFor(() =>
      expect(calls.filter((call) => call.path === '/api/v1/chat/conversations/c1/messages' && call.method === 'GET')).toHaveLength(1),
    );
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({ text: 'Hi' });
  });
});
