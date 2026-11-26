import type { EventStream, EventStreamOptions } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { detailKey } from './useAdminRequests';
import { useRequestStream } from './useRequestStream';

const openEventStream = vi.hoisted(() =>
  vi.fn<[EventStreamOptions], EventStream>(() => ({ close: vi.fn() })),
);

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream,
}));

/** How many streams the hook opened and has not closed yet. */
function liveCount(): number {
  return openEventStream.mock.results.filter(
    (result) => result.type === 'return' && vi.mocked(result.value.close).mock.calls.length === 0,
  ).length;
}

function lastOptions(): EventStreamOptions {
  const call = openEventStream.mock.lastCall;
  if (!call) throw new Error('no stream was opened');
  return call[0];
}

function changeVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

function render(number: number) {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue();
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return { ...renderHook(() => useRequestStream(number), { wrapper }), invalidate };
}

describe('useRequestStream', () => {
  beforeEach(() => {
    openEventStream.mockClear();
    changeVisibility('visible');
  });

  it('followsTheRequestTopicWhileVisible', () => {
    render(42);

    expect(liveCount()).toBe(1);
    expect(lastOptions().topics).toEqual(['support:request:42']);
  });

  it('refetchesTheRequestOnOpenChangedAndResyncOnly', () => {
    const { invalidate } = render(42);
    const { onOpen, onEvent } = lastOptions();

    onOpen?.();
    for (const name of ['support.request.changed', 'resync', 'notifications.changed']) {
      onEvent?.({ id: null, event: name, data: '{"number":42}' });
    }

    expect(invalidate).toHaveBeenCalledTimes(3);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: detailKey(42) });
  });

  it('closesOnHiddenAndReopensOnVisible', () => {
    render(42);

    changeVisibility('hidden');
    expect(liveCount()).toBe(0);

    changeVisibility('visible');
    expect(liveCount()).toBe(1);
    expect(openEventStream).toHaveBeenCalledTimes(2);
  });

  it('closesOnUnmount', () => {
    const { unmount } = render(42);

    unmount();

    expect(liveCount()).toBe(0);
  });

  it('opensNothingWhileHiddenOrForAnInvalidNumber', () => {
    render(0);
    changeVisibility('hidden');
    render(42);

    expect(openEventStream).not.toHaveBeenCalled();
  });
});
