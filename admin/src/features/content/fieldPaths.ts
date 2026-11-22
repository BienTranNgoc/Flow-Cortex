import { mediaIdOf, readText, type ContentLocale, type DocumentContent } from './documentModel';

export type PathField = 'body' | 'alt' | 'caption' | 'mediaId' | 'seoTitle' | 'seoDescription';

export interface PathDescription {
  /** Zero-based position of the section the path points into; absent for the SEO fields. */
  section?: number;
  field: PathField;
  locale?: ContentLocale;
}

const SECTION_PATH = /^sections\[(\d+)\]\.data\.(body|alt|caption)(?:\.(vi|en))?$/;
const MEDIA_PATH = /^sections\[(\d+)\]\.data\.mediaId$/;
const SEO_PATH = /^seoMeta\.(title|description)(?:\.(vi|en))?$/;

/** The field an API path names, or `undefined` for a path with no editor field (the whole `sections`, a section's `type`). */
export function describePath(path: string): PathDescription | undefined {
  const text = SECTION_PATH.exec(path);
  if (text) {
    const [, index, field, locale] = text;
    return {
      section: Number(index),
      field: field as PathField,
      ...(locale ? { locale: locale as ContentLocale } : {}),
    };
  }
  const media = MEDIA_PATH.exec(path);
  if (media) return { section: Number(media[1]), field: 'mediaId' };
  const seo = SEO_PATH.exec(path);
  if (seo) {
    const [, name, locale] = seo;
    return {
      field: name === 'title' ? 'seoTitle' : 'seoDescription',
      ...(locale ? { locale: locale as ContentLocale } : {}),
    };
  }
  return undefined;
}

export function textPath(
  section: number,
  field: 'body' | 'alt' | 'caption',
  locale: ContentLocale,
) {
  return `sections[${section}].data.${field}.${locale}`;
}

export function mediaPath(section: number) {
  return `sections[${section}].data.mediaId`;
}

export function seoPath(field: 'title' | 'description', locale: ContentLocale) {
  return `seoMeta.${field}.${locale}`;
}

/** The message of the exact path, else of its parent text (`...body` when `...body.vi` has none). */
export function errorAt(errors: Record<string, string>, path: string): string | undefined {
  const exact = errors[path];
  if (exact) return exact;
  const parent = path.replace(/\.(vi|en)$/, '');
  return parent === path ? undefined : errors[parent];
}

/**
 * Whether the field a publish refusal named is still empty in `content`. A path with no editor
 * field counts as still open; a field whose section was removed does not.
 */
export function isStillBlank(content: DocumentContent, path: string): boolean {
  const described = describePath(path);
  if (!described) return true;
  const { section, field, locale } = described;
  if (section === undefined) {
    if (!locale) return true;
    const text = field === 'seoTitle' ? content.seoMeta.title : content.seoMeta.description;
    return text[locale].trim() === '';
  }
  const target = content.sections[section];
  if (!target) return false;
  if (field === 'mediaId') return mediaIdOf(target) === undefined;
  if (!locale) return true;
  return readText(target.data[field])[locale].trim() === '';
}

/** The language tab of the first path that names one, so the editor can show it. */
export function firstLocale(paths: readonly string[]): ContentLocale | undefined {
  for (const path of paths) {
    const locale = describePath(path)?.locale;
    if (locale) return locale;
  }
  return undefined;
}
