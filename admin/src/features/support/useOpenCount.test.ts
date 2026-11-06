import { setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi, testQueryClient } from '../../test/fakeApi';
import { OPEN_COUNT_POLL_MS, useOpenCount } from './useOpenCount';

const OPEN_COUNT_PATH = '/api/v1/admin/support/requests/open-count';

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: testQueryClient() }, children);
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  setToken('admin-token-open-count', true);
});
afterEach(() => {
  vi.useRealTimers();
  setToken(null);
  vi.unstubAllGlobals();
});

describe('useOpenCount', () => {
  it('polls the open count every 60 seconds', async () => {
    let count = 3;
    const calls = stubApi({
      [`GET ${OPEN_COUNT_PATH}`]: () => ({ status: 200, body: { count } }),
    });
    const { result } = renderHook(() => useOpenCount(true), { wrapper });

    await waitFor(() => expect(result.current.data).toBe(3));
    expect(OPEN_COUNT_POLL_MS).toBe(60_000);

    count = 4;
    await vi.advanceTimersByTimeAsync(OPEN_COUNT_POLL_MS);

    await waitFor(() => expect(result.current.data).toBe(4));
    expect(calls.filter((call) => call.path === OPEN_COUNT_PATH)).toHaveLength(2);
  });

  it('sends nothing for an account that may not manage support', async () => {
    const calls = stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 1 } } });
    const { result } = renderHook(() => useOpenCount(false), { wrapper });

    await vi.advanceTimersByTimeAsync(OPEN_COUNT_POLL_MS);

    expect(result.current.data).toBeUndefined();
    expect(calls).toHaveLength(0);
  });
});
