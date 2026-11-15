import type { ChatMessage } from '@flow-cortex/api-client';

export interface ThreadState {
  /** The text of each answer still streaming: what the stored message held plus the deltas since. */
  live: Record<string, string>;
  /** Deltas that started past the text held, so a piece was missed; each one asks for a reload. */
  gaps: number;
}

export type ThreadAction =
  | { type: 'loaded'; messages: readonly ChatMessage[] }
  | { type: 'delta'; messageId: string; offset: number; text: string };

export const initialThreadState: ThreadState = { live: {}, gaps: 0 };

function applyDelta(state: ThreadState, messageId: string, offset: number, text: string): ThreadState {
  const held = state.live[messageId] ?? '';
  if (offset > held.length) {
    return { ...state, gaps: state.gaps + 1 };
  }
  const missing = offset + text.length - held.length;
  if (missing <= 0) {
    return state;
  }
  return { ...state, live: { ...state.live, [messageId]: held + text.slice(text.length - missing) } };
}

/** Streaming messages keep the longer of live and stored text; a message that ended shows its stored text. */
function applyLoaded(state: ThreadState, messages: readonly ChatMessage[]): ThreadState {
  const live = { ...state.live };
  for (const message of messages) {
    if (message.id === undefined) continue;
    if (message.role === 'assistant' && message.status === 'streaming') {
      const stored = message.body ?? '';
      if (stored.length > (live[message.id]?.length ?? 0)) live[message.id] = stored;
    } else {
      delete live[message.id];
    }
  }
  return { ...state, live };
}

export function threadReducer(state: ThreadState, action: ThreadAction): ThreadState {
  switch (action.type) {
    case 'delta':
      return applyDelta(state, action.messageId, action.offset, action.text);
    case 'loaded':
      return applyLoaded(state, action.messages);
  }
}
