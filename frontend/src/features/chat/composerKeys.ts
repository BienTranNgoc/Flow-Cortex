import { newUuidV4 } from '../support/uuid';

/** Enter sends, Shift+Enter is a newline, and an open IME composition never sends. */
export function isSubmitKey(event: { key: string; shiftKey: boolean; isComposing: boolean }): boolean {
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing;
}

export interface SendKey {
  text: string;
  key: string;
}

/** The same text sent again after a failure keeps its key, so the server can recognise the retry. */
export function nextSendKey(
  previous: SendKey | null,
  text: string,
  mint: () => string = newUuidV4,
): SendKey {
  return previous?.text === text ? previous : { text, key: mint() };
}
