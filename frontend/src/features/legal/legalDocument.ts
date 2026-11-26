import type { ContentSection } from '@flow-cortex/api-client';
import { z } from 'zod';
import { formatDateTime } from '../../lib/format';
import { activeLocale, type Locale } from '../../lib/i18n';

const localizedText = z.object({ vi: z.string().optional(), en: z.string().optional() });

const legalDocData = z.object({
  title: localizedText,
  body: localizedText,
  docVersion: z.string(),
  effectiveDate: z.string(),
});

export interface LegalDocumentView {
  readonly title: string;
  readonly body: string;
  readonly docVersion: string;
  /** `YYYY-MM-DD`, or empty. */
  readonly effectiveDate: string;
}

function inLocale(text: z.infer<typeof localizedText>, locale: Locale): string {
  const other: Locale = locale === 'vi' ? 'en' : 'vi';
  const own = text[locale]?.trim();
  return own ? own : (text[other]?.trim() ?? '');
}

/**
 * The single `legalDoc` section of a published copy in the active language, or `null` when the copy
 * holds none, holds one that does not parse, or has no readable title.
 */
export function readLegalDocument(
  sections: readonly ContentSection[],
  locale: Locale = activeLocale(),
): LegalDocumentView | null {
  const section = sections.find((candidate) => candidate.type === 'legalDoc');
  if (!section) return null;
  const parsed = legalDocData.safeParse(section.data);
  if (!parsed.success) return null;
  const title = inLocale(parsed.data.title, locale);
  if (!title) return null;
  return {
    title,
    body: inLocale(parsed.data.body, locale),
    docVersion: parsed.data.docVersion,
    effectiveDate: parsed.data.effectiveDate,
  };
}

const ISO_DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A calendar day in the language's long format; built from its parts so no time zone shifts it. */
export function formatEffectiveDate(isoDay: string, locale: Locale = activeLocale()): string {
  const match = ISO_DAY.exec(isoDay);
  if (!match) return isoDay;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? isoDay : formatDateTime(date, locale, { dateStyle: 'long' });
}

/** The instant a document was published, as a date in the language's long format. */
export function formatPublishedAt(instant: string, locale: Locale = activeLocale()): string {
  return formatDateTime(instant, locale, { dateStyle: 'long' });
}

/** The index label for the language; the server already filled a blank override with the title. */
export function labelIn(label: { vi?: string; en?: string }, locale: Locale = activeLocale()): string {
  return inLocale(label, locale);
}
