import { setToken, type EventStreamOptions } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { stubApi } from '../../test/fakeApi';
import { useSupportStream } from './useSupportStream';

interface FakeStream {
  options: EventStreamOptions;
  closed: boolean;
}

const streams = vi.hoisted(() => ({ opened: [] as FakeStream[] }));

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: (options: EventStreamOptions) => {
    const stream: FakeStream = { options, closed: false };
    streams.opened.push(stream);
    return {
      close: () => {
        stream.closed = true;
      },
    };
  },
}));

function openStreams(): FakeStream[] {
  return streams.opened.filter((stream) => !stream.closed);
}

function features(supportWidget: boolean) {
  return { status: 200, body: { supportWidget } };
}

function mount() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  const view = renderHook(() => useSupportStream(), { wrapper });
  return { ...view, invalidate };
}

function pushEvent(event: string) {
  act(() => {
    openStreams().at(-1)?.options.onEvent({ event, data: '{}', id: null });
  });
}

const flush = () => act(async () => undefined);
const SUPPORT_REQUESTS = { queryKey: ['support', 'requests'] };

describe('useSupportStream', () => {
  beforeEach(() => {
    streams.opened.length = 0;
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('refetchesSupportRequestsOnOpenChangedAndResync', async () => {
    setToken('member', true);
    stubApi({ 'GET /api/v1/features': features(true) });
    const { invalidate } = mount();
    await waitFor(() => expect(openStreams()).toHaveLength(1));
    expect(openStreams()[0]?.options.topics).toEqual(['user']);

    act(() => openStreams()[0]?.options.onOpen?.());
    await flush();
    pushEvent('support.request.changed');
    await flush();
    pushEvent('resync');
    await flush();

    expect(invalidate).toHaveBeenCalledTimes(3);
    expect(invalidate).toHaveBeenNthCalledWith(1, SUPPORT_REQUESTS);
    expect(invalidate).toHaveBeenNthCalledWith(3, SUPPORT_REQUESTS);
  });

  it('ignoresOtherEventTypes', async () => {
    setToken('member', true);
    stubApi({ 'GET /api/v1/features': features(true) });
    const { invalidate } = mount();
    await waitFor(() => expect(openStreams()).toHaveLength(1));

    pushEvent('notifications.changed');
    pushEvent('run.updated');
    await flush();

    expect(invalidate).not.toHaveBeenCalled();
  });

  it('followsNothingSignedOutOrWithTheWidgetOff', async () => {
    stubApi({ 'GET /api/v1/features': features(true) });
    const signedOut = mount();
    await flush();
    expect(openStreams()).toHaveLength(0);
    signedOut.unmount();

    setToken('member', true);
    const calls = stubApi({ 'GET /api/v1/features': features(false) });
    mount();
    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await flush();

    expect(openStreams()).toHaveLength(0);
  });

  it('stopsFollowingAfterSignOut', async () => {
    setToken('member', true);
    stubApi({ 'GET /api/v1/features': features(true) });
    mount();
    await waitFor(() => expect(openStreams()).toHaveLength(1));

    act(() => setToken(null));

    await waitFor(() => expect(openStreams()).toHaveLength(0));
  });
});
