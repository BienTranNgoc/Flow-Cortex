import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
import { useRequestDetail, useRequestList } from './useRequests';
import { useUnreadCount } from './useUnread';

function features(supportWidget: boolean) {
  return {
    status: 200,
    body: {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
      supportWidget,
      supportAssistant: false,
      chat: false,
    },
  };
}

function renderUnread() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return renderHook(() => useUnreadCount(), { wrapper });
}

afterEach(() => {
  vi.useRealTimers();
  setToken(null);
  vi.unstubAllGlobals();
});

describe('useUnreadCount', () => {
  it('polls every 60 s while signed in with the widget on, and stops after sign-out', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setToken('member', true);
    const calls = stubApi({
      'GET /api/v1/features': features(true),
      'GET /api/v1/support/requests/unread-count': { status: 200, body: { count: 2 } },
    });
    const polls = () => calls.filter((call) => call.path.endsWith('/unread-count')).length;
    const { result } = renderUnread();

    await waitFor(() => expect(result.current).toBe(2));
    expect(polls()).toBe(1);

    await act(() => vi.advanceTimersByTimeAsync(60_000));
    await waitFor(() => expect(polls()).toBe(2));

    act(() => setToken(null));
    await act(() => vi.advanceTimersByTimeAsync(180_000));

    expect(polls()).toBe(2);
    expect(result.current).toBe(0);
  });

  describe('open thread freshness', () => {
    const DETAIL = '/api/v1/support/requests/7';
    const LIST = '/api/v1/support/requests';

    function mountThread(counter: { count: number }) {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      setToken('member', true);
      const calls = stubApi({
        'GET /api/v1/features': features(true),
        'GET /api/v1/support/requests/unread-count': () => ({
          status: 200,
          body: { count: counter.count },
        }),
        [`GET ${DETAIL}`]: { status: 200, body: { number: 7, status: 'new' } },
        [`GET ${LIST}`]: { status: 200, body: { items: [] } },
      });
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      const wrapper = ({ children }: { children: ReactNode }) =>
        createElement(QueryClientProvider, { client: queryClient }, children);
      const view = renderHook(
        () => ({ unread: useUnreadCount(), detail: useRequestDetail(7), list: useRequestList() }),
        { wrapper },
      );
      const reads = (path: string) => calls.filter((call) => call.path === path).length;
      return { view, reads };
    }

    it('reads the list and every open detail again when the unread count increases', async () => {
      const counter = { count: 0 };
      const { view, reads } = mountThread(counter);
      await waitFor(() => expect(view.result.current.detail.isSuccess).toBe(true));
      await waitFor(() => expect(view.result.current.list.isSuccess).toBe(true));
      expect(reads(DETAIL)).toBe(1);
      expect(reads(LIST)).toBe(1);

      counter.count = 1;
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      await waitFor(() => expect(reads(DETAIL)).toBe(2));
      expect(reads(LIST)).toBeGreaterThanOrEqual(2);
    });

    it('makes no extra detail read while the count stays steady across polls', async () => {
      const counter = { count: 1 };
      const { view, reads } = mountThread(counter);
      await waitFor(() => expect(view.result.current.unread).toBe(1));
      await waitFor(() => expect(view.result.current.detail.isSuccess).toBe(true));

      await act(() => vi.advanceTimersByTimeAsync(60_000));
      await act(() => vi.advanceTimersByTimeAsync(60_000));

      expect(view.result.current.unread).toBe(1);
      expect(reads(DETAIL)).toBe(1);
    });
  });

  it('asks nothing while the widget switch is off', async () => {
    setToken('member', true);
    const calls = stubApi({ 'GET /api/v1/features': features(false) });
    const { result } = renderUnread();

    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));

    expect(calls.filter((call) => call.path.startsWith('/api/v1/support'))).toEqual([]);
    expect(result.current).toBe(0);
  });

  it('stops polling once a suspended member is refused', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setToken('member', true);
    const calls = stubApi({
      'GET /api/v1/features': features(true),
      'GET /api/v1/support/requests/unread-count': { status: 403, body: { status: 403 } },
    });
    const polls = () => calls.filter((call) => call.path.endsWith('/unread-count')).length;
    const { result } = renderUnread();

    await waitFor(() => expect(polls()).toBe(1));
    await act(() => vi.advanceTimersByTimeAsync(180_000));

    expect(polls()).toBe(1);
    expect(result.current).toBe(0);
  });
});
