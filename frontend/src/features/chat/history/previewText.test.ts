import { flattenPreview, PREVIEW_MAX_CHARS } from './previewText';

describe('flattenPreview', () => {
  it('removes heading marks and joins the lines', () => {
    expect(flattenPreview('## Done\nYour **video** is ready', false)).toBe('Done Your video is ready');
  });

  it('removes bullet markers but keeps numbered-list numbers', () => {
    expect(flattenPreview('- one\n* two\n+ three\n1. four\n2. five', false)).toBe('one two three 1. four 2. five');
  });

  it('removes code fences and inline code ticks and keeps the code text', () => {
    expect(flattenPreview('Run:\n```ts\nconst a = 1;\n```\nthen `npm test`', false)).toBe(
      'Run: const a = 1; then npm test',
    );
  });

  it('removes emphasis marks', () => {
    expect(flattenPreview('**bold** and *italic* and __strong__ and _em_ and ~~gone~~', false)).toBe(
      'bold and italic and strong and em and gone',
    );
  });

  it('keeps an underscore inside a word', () => {
    expect(flattenPreview('use snake_case_name and my_var here', false)).toBe('use snake_case_name and my_var here');
  });

  it('keeps the link text and drops the address', () => {
    expect(flattenPreview('See [the guide](https://example.com/a_b) now ![logo](x.png)', false)).toBe(
      'See the guide now logo',
    );
  });

  it('removes quote marks and blank lines', () => {
    expect(flattenPreview('> quoted\n\n\ntext', false)).toBe('quoted text');
  });

  it('does not cut a preview the server did not truncate', () => {
    const text = 'word '.repeat(39).trim();
    expect(flattenPreview(text, false)).toBe(text);
  });

  it('shows a message of exactly the cap whole, with no ellipsis, when the server did not truncate it', () => {
    const raw = `${'abcd '.repeat(39)}abcd`.slice(0, PREVIEW_MAX_CHARS - 1) + 'x';
    expect([...raw]).toHaveLength(PREVIEW_MAX_CHARS);
    expect(flattenPreview(raw, false)).toBe(raw);
  });

  it('ends a truncated preview on a whole word followed by an ellipsis', () => {
    const raw = `${'abcd '.repeat(39)}abcd`.slice(0, PREVIEW_MAX_CHARS - 1) + 'x';
    expect([...raw]).toHaveLength(PREVIEW_MAX_CHARS);
    const out = flattenPreview(raw, true);
    expect(out.endsWith('…')).toBe(true);
    expect(out.slice(0, -1).split(' ').every((w) => w === 'abcd')).toBe(true);
    expect([...out].length).toBeLessThanOrEqual(PREVIEW_MAX_CHARS);
  });

  it('keeps a single unbroken truncated word and adds the ellipsis', () => {
    expect(flattenPreview('a'.repeat(PREVIEW_MAX_CHARS), true)).toBe(`${'a'.repeat(PREVIEW_MAX_CHARS)}…`);
  });

  it('counts a character outside the BMP once', () => {
    expect(flattenPreview('😀'.repeat(150), false)).toBe('😀'.repeat(150));
  });
});
