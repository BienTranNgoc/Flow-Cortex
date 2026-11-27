import type { ContentDocumentSummary } from '@flow-cortex/api-client';
import { documentTitle } from './documentTitle';
import { LEGAL_DOC, type ContentLocale } from './documentModel';
import { useDocumentsQuery } from './useContentDocuments';

/** The registered documents that hold a legal text, in registration order; empty until the list loads. */
export function useLegalDocuments(): ContentDocumentSummary[] {
  const { data } = useDocumentsQuery();
  return (data ?? []).filter((document) => document.sectionTypes.includes(LEGAL_DOC));
}

/** The name of the document in `locale`, falling back to the console language and then to the slug. */
export function legalDocumentName(
  documents: readonly ContentDocumentSummary[],
  slug: string,
  locale: ContentLocale,
  language: string,
): string {
  const summary = documents.find((document) => document.slug === slug);
  if (!summary) return slug;
  return summary.title[locale] || documentTitle(summary, language);
}
