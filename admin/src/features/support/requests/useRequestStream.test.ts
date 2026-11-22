import type { EventStreamOptions, StreamEvent } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { detailKey } from './useAdminRequests';
import { useRequestStream } from './useRequestStream';

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

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

function render(number: number) {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  const hook = renderHook(() => useRequestStream(number), { wrapper });
  return { ...hook, invalidate };
}

function event(name: string): StreamEvent {
  return { id: null, event: name, data: '{"number":42}' };
}

describe('useRequestStream', () => {
  beforeEach(() => {
    streams.opened.length = 0;
    setVisibility('visible');
  });

  it('followsTheRequestTopicWhileVisible', () => {
    render(42);

    expect(openStreams()).toHaveLength(1);
    expect(openStreams()[0]?.options.topics).toEqual(['support:request:42']);
  });

  it('refetchesTheRequestOnOpenChangedAndResyncOnly', () => {
    const { invalidate } = render(42);
    const options = openStreams()[0]!.options;

    options.onOpen?.();
    options.onEvent?.(event('support.request.changed'));
    options.onEvent?.(event('resync'));
    options.onEvent?.(event('notifications.changed'));

    expect(invalidate).toHaveBeenCalledTimes(3);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: detailKey(42) });
  });

  it('closesOnHiddenAndReopensOnVisible', () => {
    render(42);

    act(() => setVisibility('hidden'));
    expect(openStreams()).toHaveLength(0);

    act(() => setVisibility('visible'));
    expect(openStreams()).toHaveLength(1);
    expect(streams.opened).toHaveLength(2);
  });

  it('closesOnUnmount', () => {
    const { unmount } = render(42);

    unmount();

    expect(openStreams()).toHaveLength(0);
  });

  it('opensNothingWhileHiddenOrForAnInvalidNumber', () => {
    render(0);
    act(() => setVisibility('hidden'));
    render(42);

    expect(streams.opened).toHaveLength(0);
  });
});
