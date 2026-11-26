import type { ContentSection, ContentSeoMeta } from '@flow-cortex/api-client';

export const LOCALES = ['vi', 'en'] as const;
export type ContentLocale = (typeof LOCALES)[number];
export type LocalizedText = Record<ContentLocale, string>;

/** The bounds the API enforces on a save; the editor shows them, the API stays the arbiter. */
export const MAX_TEXT_CHARS = 50_000;
export const MAX_SEO_TITLE_CHARS = 200;
export const MAX_SEO_DESCRIPTION_CHARS = 500;
export const MAX_TITLE_CHARS = 200;
export const MAX_DOC_VERSION_CHARS = 32;

export const RICH_TEXT = 'richText';
export const MEDIA = 'media';
export const LEGAL_DOC = 'legalDoc';
export const LEGAL_INDEX = 'legalIndex';
export const DOC_VERSION = 'docVersion';
export const EFFECTIVE_DATE = 'effectiveDate';

export interface Section {
  /** Client-side identity that keeps a section's DOM and focus with it across moves; never sent. */
  id: string;
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

let sectionSeq = 0;
const nextSectionId = () => `section-${++sectionSeq}`;

/** Content from the wire shape; anything that is not a section object is dropped. */
export function parseContent(sections: unknown, seoMeta: unknown): DocumentContent {
  const list = Array.isArray(sections) ? sections : [];
  const seo = isRecord(seoMeta) ? seoMeta : {};
  return {
    sections: list.filter(isRecord).map((section) => ({
      id: nextSectionId(),
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

/** The content as the API takes it: the client-side section ids are left out. */
export function toWire(content: DocumentContent): {
  sections: Omit<Section, 'id'>[];
  seoMeta: SeoMeta;
} {
  return {
    sections: content.sections.map(({ type, schemaVersion, data }) => ({
      type,
      schemaVersion,
      data,
    })),
    seoMeta: content.seoMeta,
  };
}

/** An empty section of a built-in type. A media section has no `mediaId` until one is picked. */
export function newSection(type: string): Section {
  const id = nextSectionId();
  if (type === LEGAL_DOC) {
    return {
      id,
      type,
      schemaVersion: 1,
      data: {
        title: { ...EMPTY_TEXT },
        body: { ...EMPTY_TEXT },
        docVersion: '',
        effectiveDate: '',
      },
    };
  }
  if (type === LEGAL_INDEX) return { id, type, schemaVersion: 1, data: { items: [] } };
  if (type === MEDIA) {
    return {
      id,
      type,
      schemaVersion: 1,
      data: { alt: { ...EMPTY_TEXT }, caption: { ...EMPTY_TEXT } },
    };
  }
  return { id, type, schemaVersion: 1, data: { body: { ...EMPTY_TEXT } } };
}

/** The section types the console has an editor for. */
export function isEditable(type: string): boolean {
  return type === RICH_TEXT || type === MEDIA || type === LEGAL_DOC || type === LEGAL_INDEX;
}

/** A plain string field of a section, such as the version label or the effective date. */
export function withField(section: Section, field: string, value: string): Section {
  return { ...section, data: { ...section.data, [field]: value } };
}

/** One row of a `legalIndex` section; a label key is present only when staff typed an override. */
export interface IndexItem {
  slug: string;
  visible: boolean;
  label: Partial<LocalizedText>;
}

/** The rows of a `legalIndex` section from the wire; a row without a slug is dropped. */
export function indexItems(section: Section): IndexItem[] {
  const items = section.data.items;
  if (!Array.isArray(items)) return [];
  return items.filter(isRecord).flatMap((item) => {
    if (typeof item.slug !== 'string') return [];
    const label = isRecord(item.label) ? item.label : {};
    return [
      {
        slug: item.slug,
        visible: item.visible !== false,
        label: {
          ...(typeof label.vi === 'string' ? { vi: label.vi } : {}),
          ...(typeof label.en === 'string' ? { en: label.en } : {}),
        },
      },
    ];
  });
}

/** The section holding `items`; a row with no override saves without a `label` key. */
export function withIndexItems(section: Section, items: readonly IndexItem[]): Section {
  return {
    ...section,
    data: {
      ...section.data,
      items: items.map(({ slug, visible, label }) => ({
        slug,
        visible,
        ...(Object.keys(label).length > 0 ? { label } : {}),
      })),
    },
  };
}

/** The row with the label override of one language set, or removed when `value` is empty. */
export function withItemLabel(item: IndexItem, locale: ContentLocale, value: string): IndexItem {
  const label = { ...item.label };
  if (value === '') delete label[locale];
  else label[locale] = value;
  return { ...item, label };
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
