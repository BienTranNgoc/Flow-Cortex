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
  passwordReset: false,
  supportWidget: false,
  supportAssistant: false,
  personalAccessTokens: false,
  onboarding: false,
  chat: false,
  drive: false,
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

  it('reads the features again on a mount once its answer is 30 seconds old', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      const calls = stubApi({
        'GET /api/v1/features': { status: 200, body: { ...ALL_OFF, runCancellation: true } },
      });
      const { wrapper } = setup();
      const first = renderHook(() => useFeatures(), { wrapper });
      await waitFor(() => expect(first.result.current.runCancellation).toBe(true));

      vi.setSystemTime(Date.now() + 29_000);
      renderHook(() => useFeatures(), { wrapper });
      expect(calls).toHaveLength(1);

      vi.setSystemTime(Date.now() + 2_000);
      renderHook(() => useFeatures(), { wrapper });
      await waitFor(() => expect(calls).toHaveLength(2));
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the last answer when a later read fails', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      let reply: { status: number; body: unknown } = {
        status: 200,
        body: { ...ALL_OFF, runCancellation: true },
      };
      const calls = stubApi({ 'GET /api/v1/features': () => reply });
      const { queryClient, wrapper } = setup();
      const first = renderHook(() => useFeatures(), { wrapper });
      await waitFor(() => expect(first.result.current.runCancellation).toBe(true));

      reply = { status: 500, body: { status: 500 } };
      vi.setSystemTime(Date.now() + 31_000);
      const second = renderHook(() => useFeatures(), { wrapper });
      await waitFor(() => expect(calls).toHaveLength(2));
      // The call is recorded before its reply is built, so wait for the failure itself.
      await waitFor(() =>
        expect(queryClient.getQueryCache().getAll()[0]?.state.status).toBe('error'),
      );

      expect(second.result.current.runCancellation).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});
