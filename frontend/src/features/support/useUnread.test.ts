import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
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
  it('makesNoUnreadRequestOverFiveMinutesWithoutStreamEvents', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setToken('member', true);
    const calls = stubApi({
      'GET /api/v1/features': features(true),
      'GET /api/v1/support/requests/unread-count': { status: 200, body: { count: 2 } },
    });
    const reads = () => calls.filter((call) => call.path.endsWith('/unread-count')).length;
    const { result } = renderUnread();

    await waitFor(() => expect(result.current).toBe(2));
    expect(reads()).toBe(1);

    await act(() => vi.advanceTimersByTimeAsync(5 * 60_000));

    expect(reads()).toBe(1);
    expect(result.current).toBe(2);
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
});
