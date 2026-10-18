import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../test/fakeApi';
import { useFeatures } from './useFeatures';

const ALL_OFF = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
};

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return { queryClient, wrapper };
}

describe('useFeatures', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reports every feature off while the request is in flight, then what the backend answered', async () => {
    let answer: (reply: { status: number; body: unknown }) => void = () => {};
    stubApi({
      'GET /api/v1/features': () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    });
    const { wrapper } = setup();
    const { result } = renderHook(() => useFeatures(), { wrapper });

    expect(result.current).toEqual(ALL_OFF);

    answer({ status: 200, body: { ...ALL_OFF, uploadContentCheck: true, credits: true } });
    await waitFor(() =>
      expect(result.current).toEqual({ ...ALL_OFF, uploadContentCheck: true, credits: true }),
    );
  });

  it('reports every feature off once the request has failed', async () => {
    stubApi({ 'GET /api/v1/features': { status: 500, body: { status: 500 } } });
    const { queryClient, wrapper } = setup();
    const { result } = renderHook(() => useFeatures(), { wrapper });

    await waitFor(() => expect(queryClient.getQueryCache().getAll()[0]?.state.status).toBe('error'));
    expect(result.current).toEqual(ALL_OFF);
  });

  it('asks the backend once however many components read the features', async () => {
    const calls = stubApi({
      'GET /api/v1/features': { status: 200, body: { ...ALL_OFF, credits: true } },
    });
    const { wrapper } = setup();
    const first = renderHook(() => useFeatures(), { wrapper });
    await waitFor(() => expect(first.result.current.credits).toBe(true));

    const second = renderHook(() => useFeatures(), { wrapper });

    expect(second.result.current.credits).toBe(true);
    expect(calls).toHaveLength(1);
  });
});
