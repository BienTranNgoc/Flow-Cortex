/**
 * Mirrors the cap `ConversationStore` applies to a list row's preview. The member app does not cut
 * by it (the server flags a cut), it only sizes the texts the tests build.
 */
export const PREVIEW_MAX_CHARS = 200;

const WORD_CHAR = '\\p{L}\\p{N}_';

/**
 * One plain line from a markdown message: block marks, emphasis, code ticks and link addresses are
 * dropped, lines are joined by a space. `truncated` is the server's `previewTruncated`: a text it
 * cut ends on a whole word followed by an ellipsis; a text of exactly the cap is shown whole.
 */
export function flattenPreview(raw: string, truncated: boolean): string {
  const lines = raw
    .split(/\r?\n/)
    .filter((line) => !/^\s*(```|~~~)/.test(line))
    .filter((line) => !/^\s*([-*_])(\s*\1){2,}\s*$/.test(line))
    .map((line) =>
      line
        .replace(/^\s{0,3}#{1,6}\s+/, '')
        .replace(/^\s*>+\s?/, '')
        .replace(/^\s*[-*+]\s+/, ''),
    );
  const flat = lines
    .join(' ')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`+([^`]*)`+/g, '$1')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/~~(.+?)~~/g, '$1')
    .replace(/\*(?!\s)(.+?)(?<!\s)\*/g, '$1')
    .replace(new RegExp(`(?<![${WORD_CHAR}])(_{1,2})(?=\\S)(.+?)(?<=\\S)\\1(?![${WORD_CHAR}])`, 'gu'), '$2')
    .replace(/\s+/g, ' ')
    .trim();
  if (!truncated) return flat;
  const cut = flat.lastIndexOf(' ');
  return `${cut > 0 ? flat.slice(0, cut) : flat}…`;
}
