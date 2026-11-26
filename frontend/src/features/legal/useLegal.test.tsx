import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
import {
  HISTORY_PAGE_SIZE,
  useLegalHistory,
  useLegalIndex,
  usePublishedLegalDocument,
} from './useLegal';

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return { wrapper };
}

const PRIVACY = 'GET /api/v1/content/documents/legal-privacy';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usePublishedLegalDocument', () => {
  it('resolves a 404 to null, which the pages read as not available', async () => {
    stubApi({ [PRIVACY]: { status: 404, body: { status: 404 } } });
    const { result } = renderHook(() => usePublishedLegalDocument('privacy'), setup());

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it('keeps any other failure an error', async () => {
    stubApi({ [PRIVACY]: { status: 500, body: { status: 500 } } });
    const { result } = renderHook(() => usePublishedLegalDocument('privacy'), setup());

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it('sends no request for a segment that cannot name a legal document', () => {
    const calls = stubApi({});
    const { result } = renderHook(() => usePublishedLegalDocument('Privacy'), setup());

    expect(result.current.fetchStatus).toBe('idle');
    expect(calls).toHaveLength(0);
  });
});

describe('useLegalIndex', () => {
  const INDEX = 'GET /api/v1/content/legal-index';

  it('reads a 404 as an empty index', async () => {
    stubApi({ [INDEX]: { status: 404, body: { status: 404 } } });
    const { result } = renderHook(() => useLegalIndex(), setup());

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it('keeps a server failure an error', async () => {
    stubApi({ [INDEX]: { status: 503, body: { status: 503 } } });
    const { result } = renderHook(() => useLegalIndex(), setup());

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useLegalHistory', () => {
  it('asks for twenty publications at the zero-based page', async () => {
    stubApi({
      'GET /api/v1/content/documents/legal-terms/history': {
        status: 200,
        body: { items: [], page: 1, size: HISTORY_PAGE_SIZE, total: 0 },
      },
    });
    const { result } = renderHook(() => useLegalHistory('terms', 1), setup());

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(HISTORY_PAGE_SIZE).toBe(20);
    expect(vi.mocked(fetch).mock.calls.map((call) => String(call[0]))).toEqual([
      '/api/v1/content/documents/legal-terms/history?page=1&size=20',
    ]);
  });
});
