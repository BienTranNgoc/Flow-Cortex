import { MAX_TITLE_LENGTH, validateTitle } from './chatTitle';

describe('validateTitle', () => {
  it('trims and accepts 1 to 80 characters', () => {
    expect(validateTitle('  Product video  ')).toEqual({ ok: true, title: 'Product video', length: 13 });
    expect(validateTitle('a'.repeat(MAX_TITLE_LENGTH))).toMatchObject({ ok: true });
  });

  it('refuses a title that is empty after trimming', () => {
    expect(validateTitle('   ')).toEqual({ ok: false, reason: 'empty', length: 0 });
  });

  it('refuses more than 80 characters after trimming and reports the length', () => {
    expect(validateTitle(`  ${'a'.repeat(81)}  `)).toEqual({ ok: false, reason: 'tooLong', length: 81 });
  });

  it('counts a character outside the BMP once', () => {
    expect(validateTitle('😀'.repeat(MAX_TITLE_LENGTH))).toMatchObject({ ok: true });
  });
});
