// Character.isWhitespace in Java: space separators except the no-break ones (U+00A0, U+2007,
// U+202F), line and paragraph separators, and the ASCII controls \t \n \v \f \r and U+001C-U+001F.
const JAVA_WHITESPACE =
  '\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000';
const EDGE = new RegExp(`^[${JAVA_WHITESPACE}]+|[${JAVA_WHITESPACE}]+$`, 'g');

/** `String.strip()` as the backend applies it: JS `trim()` also removes NBSP and U+FEFF, Java does not. */
export function javaStrip(text: string): string {
  return text.replace(EDGE, '');
}

/** Length in code points after `javaStrip`, the way the server counts a limited text. */
export function strippedLength(text: string): number {
  return [...javaStrip(text)].length;
}
