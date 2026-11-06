import i18next from 'i18next';
import { describeLocaleParity, flattenLocale, localeAreas } from './testing';

describe('flattenLocale', () => {
  it('joins nested keys with dots', () => {
    expect([...flattenLocale({ a: 'x', b: { c: 'y', d: { e: 'z' } } })]).toEqual([
      ['a', 'x'],
      ['b.c', 'y'],
      ['b.d.e', 'z'],
    ]);
  });
});

describe('localeAreas', () => {
  it('lists each area once, sorted', () => {
    expect(
      localeAreas({ './vi/b.json': {}, './en/b.json': {}, './vi/a.json': {}, './en/a.json': {} }),
    ).toEqual(['a', 'b']);
  });
});

const files = {
  './vi/common.json': { title: 'Tiêu đề', nested: { ok: 'Đồng ý' } },
  './en/common.json': { title: 'Title', nested: { ok: 'OK' } },
};
const registered = i18next.createInstance();
void registered.init({
  resources: { en: { translation: { common: files['./en/common.json'] } } },
});

describeLocaleParity(files, registered);
