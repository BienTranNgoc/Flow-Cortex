import type { ChatMessage } from '@flow-cortex/api-client';
import { initialThreadState, threadReducer, type ThreadState } from './threadReducer';

const streaming = (id: string, body?: string): ChatMessage => ({
  id,
  seq: 2,
  role: 'assistant',
  kind: 'text',
  status: 'streaming',
  ...(body === undefined ? {} : { body }),
});

const apply = (state: ThreadState, offset: number, text: string, messageId = 'a1') =>
  threadReducer(state, { type: 'delta', messageId, offset, text });

describe('threadReducer', () => {
  it('appends a delta that starts at the end of the text held', () => {
    let state = apply(initialThreadState, 0, 'Hel');
    state = apply(state, 3, 'lo');

    expect(state.live.a1).toBe('Hello');
  });

  it('ignores a delta already held, whole', () => {
    const held = apply(initialThreadState, 0, 'Hello');

    expect(held.live.a1).toBe('Hello');
    expect(apply(held, 0, 'Hel')).toBe(held);
    expect(apply(held, 2, 'llo')).toBe(held);
  });

  it('appends only the part of an overlapping delta past the text held', () => {
    const held = apply(initialThreadState, 0, 'Hello');

    expect(apply(held, 3, 'lo world').live.a1).toBe('Hello world');
  });

  it('keeps the text and counts a gap when a delta starts past the end', () => {
    const held = apply(initialThreadState, 0, 'Hello');
    const gapped = apply(held, 9, 'x');

    expect(gapped.live.a1).toBe('Hello');
    expect(gapped.gaps).toBe(held.gaps + 1);
  });

  it('seeds a streaming message from its stored text when the thread loads', () => {
    const state = threadReducer(initialThreadState, {
      type: 'loaded',
      messages: [streaming('a1', 'Stored so')],
    });

    expect(state.live.a1).toBe('Stored so');
    expect(apply(state, 9, ' far').live.a1).toBe('Stored so far');
  });

  it('keeps live text that is longer than the stored text on a reload', () => {
    const held = apply(initialThreadState, 0, 'Hello world');
    const state = threadReducer(held, { type: 'loaded', messages: [streaming('a1', 'Hello')] });

    expect(state.live.a1).toBe('Hello world');
  });

  it('drops live text once the stored message is no longer streaming', () => {
    const held = apply(initialThreadState, 0, 'Hello wor');
    expect(held.live.a1).toBe('Hello wor');
    const state = threadReducer(held, {
      type: 'loaded',
      messages: [{ ...streaming('a1', 'Hello world'), status: 'done' }],
    });

    expect(state.live).toEqual({});
  });

  it('keeps the live text of a message the loaded thread does not list yet', () => {
    const held = apply(initialThreadState, 0, 'Hi');
    const state = threadReducer(held, { type: 'loaded', messages: [] });

    expect(state.live.a1).toBe('Hi');
  });
});
