import { parseContent } from './documentModel';
import { describePath, errorAt, firstLocale, isStillBlank } from './fieldPaths';

describe('describePath', () => {
  it.each([
    ['sections[3].data.body.en', { section: 3, field: 'body', locale: 'en' }],
    ['sections[0].data.alt.vi', { section: 0, field: 'alt', locale: 'vi' }],
    ['sections[2].data.caption', { section: 2, field: 'caption' }],
    ['sections[1].data.mediaId', { section: 1, field: 'mediaId' }],
    ['seoMeta.title.vi', { field: 'seoTitle', locale: 'vi' }],
    ['seoMeta.description', { field: 'seoDescription' }],
  ])('reads %s', (path, expected) => {
    expect(describePath(path)).toEqual(expected);
  });

  it.each(['sections', 'seoMeta', 'sections[1].type', 'sections[1].data.body.fr'])(
    'has no editor field for %s',
    (path) => {
      expect(describePath(path)).toBeUndefined();
    },
  );
});

describe('errorAt', () => {
  it('prefers the exact path and falls back to the parent text', () => {
    const errors = { 'sections[0].data.body': 'required', 'sections[0].data.body.en': 'too long' };
    expect(errorAt(errors, 'sections[0].data.body.en')).toBe('too long');
    expect(errorAt(errors, 'sections[0].data.body.vi')).toBe('required');
    expect(errorAt(errors, 'seoMeta.title.vi')).toBeUndefined();
  });
});

describe('firstLocale', () => {
  it('is the language of the first path that names one', () => {
    expect(firstLocale(['sections[0].data.mediaId', 'seoMeta.title.en', 'seoMeta.title.vi'])).toBe(
      'en',
    );
    expect(firstLocale(['sections', 'sections[0].data.mediaId'])).toBeUndefined();
  });
});

describe('isStillBlank', () => {
  const content = parseContent(
    [
      { type: 'richText', schemaVersion: 1, data: { body: { vi: ' ', en: 'Hello' } } },
      { type: 'media', schemaVersion: 1, data: { alt: { vi: 'a', en: 'b' } } },
    ],
    { title: { vi: 'T', en: '' }, description: { vi: '', en: '' } },
  );

  it('follows the text the path points at, and the library item for a media section', () => {
    expect(isStillBlank(content, 'sections[0].data.body.vi')).toBe(true);
    expect(isStillBlank(content, 'sections[0].data.body.en')).toBe(false);
    expect(isStillBlank(content, 'sections[1].data.mediaId')).toBe(true);
    expect(isStillBlank(content, 'seoMeta.title.en')).toBe(true);
    expect(isStillBlank(content, 'seoMeta.title.vi')).toBe(false);
  });

  it('treats a removed section as done and an unknown path as still open', () => {
    expect(isStillBlank(content, 'sections[7].data.body.vi')).toBe(false);
    expect(isStillBlank(content, 'sections')).toBe(true);
  });
});
