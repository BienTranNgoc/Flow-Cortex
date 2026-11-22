import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import {
  openStreams,
  setVisibility as dispatchVisibility,
  streams,
} from '../../test/fakeEventStream';
import { useNotificationStream } from './useNotificationStream';

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: (await import('../../test/fakeEventStream')).openFakeEventStream,
}));

function setVisibility(state: 'visible' | 'hidden') {
  act(() => dispatchVisibility(state));
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
