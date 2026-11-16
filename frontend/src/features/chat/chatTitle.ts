export const MAX_TITLE_LENGTH = 80;

export type TitleCheck =
  | { ok: true; title: string; length: number }
  | { ok: false; reason: 'empty' | 'tooLong'; length: number };

/** A title is 1 to 80 characters after trimming; the length counts code points, as the server does. */
export function validateTitle(raw: string): TitleCheck {
  const title = raw.trim();
  const length = [...title].length;
  if (length === 0) return { ok: false, reason: 'empty', length };
  if (length > MAX_TITLE_LENGTH) return { ok: false, reason: 'tooLong', length };
  return { ok: true, title, length };
}
