import type { ContentDocumentSummary } from '@flow-cortex/api-client';

/** The document's title in the console language, falling back to the other language, then to the slug. */
export function documentTitle(
  document: Pick<ContentDocumentSummary, 'slug' | 'title'>,
  language: string,
): string {
  const [first, second] = language.startsWith('vi')
    ? (['vi', 'en'] as const)
    : (['en', 'vi'] as const);
  return document.title[first] || document.title[second] || document.slug;
}
