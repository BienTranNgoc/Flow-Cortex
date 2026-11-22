import type { EventStreamOptions } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { useNotificationStream } from './useNotificationStream';

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
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

function mount() {
  const queryClient = new QueryClient();
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  const view = renderHook(() => useNotificationStream(), { wrapper });
  return { ...view, invalidate };
}

function pushEvent(event: string) {
  act(() => {
    openStreams().at(-1)?.options.onEvent({ event, data: '{}', id: null });
  });
}

const flush = () => act(async () => undefined);

describe('useNotificationStream', () => {
  beforeEach(() => {
    streams.opened.length = 0;
    setVisibility('visible');
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('refetchesOnOpenChangedAndResync', async () => {
    const { invalidate } = mount();

    act(() => openStreams()[0]?.options.onOpen?.());
    await flush();
    pushEvent('notifications.changed');
    await flush();
    pushEvent('resync');
    await flush();
    expect(invalidate).toHaveBeenCalledTimes(3);

    pushEvent('run.updated');
    await flush();
    expect(invalidate).toHaveBeenCalledTimes(3);
  });

  it('aBurstDuringARefetchCausesOneTrailingRefetch', async () => {
    const { invalidate } = mount();
    let finish: () => void = () => undefined;
    invalidate.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );

    pushEvent('notifications.changed');
    pushEvent('notifications.changed');
    pushEvent('notifications.changed');
    expect(invalidate).toHaveBeenCalledTimes(1);

    await act(async () => {
      finish();
    });

    expect(invalidate).toHaveBeenCalledTimes(2);
  });
});
