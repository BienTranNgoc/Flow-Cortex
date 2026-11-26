import type { EventStream, EventStreamOptions } from '@flow-cortex/api-client';

/**
 * A stand-in for `openEventStream`: a test mocks the api-client with
 * `openEventStream: (await import('<path>/test/fakeEventStream')).openFakeEventStream` and drives
 * the recorded options' callbacks itself.
 */
export interface FakeStream {
  options: EventStreamOptions;
  closed: boolean;
}

export const streams = { opened: [] as FakeStream[] };

export function openFakeEventStream(options: EventStreamOptions): EventStream {
  const stream: FakeStream = { options, closed: false };
  streams.opened.push(stream);
  return {
    close: () => {
      stream.closed = true;
    },
  };
}

export function openStreams(): FakeStream[] {
  return streams.opened.filter((stream) => !stream.closed);
}

export function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}
