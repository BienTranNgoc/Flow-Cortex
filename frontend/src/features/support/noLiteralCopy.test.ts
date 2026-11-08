import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const VIETNAMESE = /[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]/i;
const IS_TEST = /\.test\.tsx?$/;

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(entry.name) && !IS_TEST.test(entry.name) ? [path] : [];
  });
}

describe('support feature copy', () => {
  it('keeps Vietnamese text in the locale files, out of the source', () => {
    const files = sourceFiles(import.meta.dirname);
    expect(files.length).toBeGreaterThan(0);

    const offenders = files.filter((file) => VIETNAMESE.test(readFileSync(file, 'utf8')));

    expect(offenders).toEqual([]);
  });
});
