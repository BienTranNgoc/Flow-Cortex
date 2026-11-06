import { javaStrip, strippedLength } from './javaStrip';

const char = (code: number) => String.fromCharCode(code);

describe('javaStrip', () => {
  it('strips what Java String.strip() strips', () => {
    const noise = [0x20, 0x09, 0x0a, 0x0d, 0x0b, 0x0c, 0x1c, 0x2003, 0x3000].map(char).join('');
    const tail = [0x2028, 0x2029, 0x20].map(char).join('');
    expect(javaStrip(`${noise}hi${tail}`)).toBe('hi');
  });

  it('keeps the no-break spaces and the byte-order mark Java does not strip', () => {
    for (const mark of [0xa0, 0x2007, 0x202f, 0xfeff].map(char)) {
      expect(javaStrip(`a${mark}`)).toBe(`a${mark}`);
      expect(javaStrip(`${mark}a`)).toBe(`${mark}a`);
    }
  });
});

describe('strippedLength', () => {
  it('counts code points, so a trailing NBSP or U+FEFF at the limit is one over', () => {
    const limit = 'a'.repeat(1000);
    expect(strippedLength(limit + char(0xa0))).toBe(1001);
    expect(strippedLength(limit + char(0xfeff))).toBe(1001);
    expect(strippedLength(limit + char(0x20))).toBe(1000);
  });

  it('counts an emoji once', () => {
    expect(strippedLength(' 😀 ')).toBe(1);
  });
});
