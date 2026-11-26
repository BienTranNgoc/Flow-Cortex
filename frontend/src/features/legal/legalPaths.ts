const DOC_SEGMENT = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const PATH_PREFIX = '/legal/';

/** The content slug of a `/legal/:doc` segment, or `null` when the segment cannot name a legal document. */
export function slugForDoc(doc: string): string | null {
  return DOC_SEGMENT.test(doc) ? `legal-${doc}` : null;
}

// The API accepts history pages 0..100000, i.e. 1..100001 in the address bar.
const MAX_PAGE = 100_001;

/** The 1-based history page of a `?page=` value; anything that is not a page the API accepts reads as page 1. */
export function pageOf(raw: string | null): number {
  const page = Number(raw);
  return Number.isInteger(page) && page >= 1 && page <= MAX_PAGE ? page : 1;
}

/** Whether publications remain after the 1-based `page` of `size` entries out of `total`. */
export function hasNextPage(page: number, size: number, total: number): boolean {
  return page * size < total;
}

/** The document segment of an index entry path such as `/legal/privacy`, or `null` for any other path. */
export function docOfPath(path: string): string | null {
  if (!path.startsWith(PATH_PREFIX)) return null;
  const doc = path.slice(PATH_PREFIX.length);
  return DOC_SEGMENT.test(doc) ? doc : null;
}
