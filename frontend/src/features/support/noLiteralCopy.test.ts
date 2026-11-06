import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const VIETNAMESE = /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i;
const DIR = import.meta.dirname;

describe('support feature copy', () => {
  it('keeps Vietnamese text in the locale files, out of the source', () => {
    const offenders = readdirSync(DIR)
      .filter((name) => /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name))
      .filter((name) => VIETNAMESE.test(readFileSync(join(DIR, name), 'utf8')));

    expect(offenders).toEqual([]);
  });
});
