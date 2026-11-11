import type { ContentSection, ContentSeoMeta } from '@flow-cortex/api-client';

export const LOCALES =['vi', 'en'] as const;
export type ContentLocale = (typeof LOCALES)[number];
export type LocalizedText = Record<ContentLocale, string>;

/** The bounds the API enforces on a save; the editor shows them, the API stays the arbiter. */
export const MAX_TEXT_CHARS = 50_000;
export const MAX_SEO_TITLE_CHARS = 200;
export const MAX_SEO_DESCRIPTION_CHARS = 500;
export const MAX_SECTIONS = 100;

export const RICH_TEXT = 'richText';
export const MEDIA = 'media';

export interface Section {
  type: string;
  schemaVersion: number;
  data: Record<string, unknown>;
}

export interface SeoMeta {
  title: LocalizedText;
  description: LocalizedText;
}

export interface DocumentContent {
  sections: Section[];
  seoMeta: SeoMeta;
}

export const EMPTY_TEXT: LocalizedText = { vi: '', en: '' };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** A `{vi, en}` value from the wire; a missing or mistyped language reads as empty. */
export function readText(value: unknown): LocalizedText {
  const source = isRecord(value) ? value : {};
  return {
    vi: typeof source.vi === 'string' ? source.vi : '',
    en: typeof source.en === 'string' ? source.en : '',
  };
}

/** Counts characters the way the API does: by code point, so an emoji is one. */
export function characterCount(text: string): number {
  return Array.from(text).length;
}

/** Content from the wire shape; anything that is not a section object is dropped. */
export function parseContent(sections: unknown, seoMeta: unknown): DocumentContent {
  const list = Array.isArray(sections) ? sections : [];
  const seo = isRecord(seoMeta) ? seoMeta : {};
  return {
    sections: list.filter(isRecord).map((section) => ({
      type: typeof section.type === 'string' ? section.type : '',
      schemaVersion: typeof section.schemaVersion === 'number' ? section.schemaVersion : 1,
      data: isRecord(section.data) ? section.data : {},
    })),
    seoMeta: { title: readText(seo.title), description: readText(seo.description) },
  };
}

/** Content from a document, snapshot or publication the API answered. */
export function fromWire(wire: {
  sections: ContentSection[];
  seoMeta: ContentSeoMeta;
}): DocumentContent {
  return parseContent(wire.sections, wire.seoMeta);
}

/** An empty section of a built-in type. A media section has no `mediaId` until one is picked. */
export function newSection(type: string): Section {
  if (type === MEDIA) {
    return {
      type,
      schemaVersion: 1,
      data: { alt: { ...EMPTY_TEXT }, caption: { ...EMPTY_TEXT } },
    };
  }
  return { type, schemaVersion: 1, data: { body: { ...EMPTY_TEXT } } };
}

/** The section types the console has an editor for. */
export function isEditable(type: string): boolean {
  return type === RICH_TEXT || type === MEDIA;
}

export function withText(
  section: Section,
  field: string,
  locale: ContentLocale,
  value: string,
): Section {
  return {
    ...section,
    data: { ...section.data, [field]: { ...readText(section.data[field]), [locale]: value } },
  };
}

export function withMedia(section: Section, mediaId: string): Section {
  return { ...section, data: { ...section.data, mediaId } };
}

export function mediaIdOf(section: Section): string | undefined {
  const id = section.data.mediaId;
  return typeof id === 'string' && id !== '' ? id : undefined;
}

/** `items` with the element at `index` moved by `offset`; out-of-range moves return it unchanged. */
export function moved<T>(items: readonly T[], index: number, offset: -1 | 1): T[] {
  const target = index + offset;
  if (target < 0 || target >= items.length) return [...items];
  const next = [...items];
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item as T);
  return next;
}
