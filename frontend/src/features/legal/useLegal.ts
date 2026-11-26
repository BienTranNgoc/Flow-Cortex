import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  ApiError,
  getContentDocumentPublication,
  getLegalIndex,
  getPublishedContentDocument,
  listContentDocumentHistory,
  unwrap,
} from '@flow-cortex/api-client';
import { slugForDoc } from './legalPaths';

export const HISTORY_PAGE_SIZE = 20;

// The server answers each published read from a ten-second cache; asking again sooner gains nothing.
const FRESH_MS = 10_000;

// The legal routes are public: no bearer is sent, so an expired member token cannot turn a read
// into a 401 that signs the member out.

/** A 404 is an answer ("not available"), so it resolves to `null`; any other failure stays an error. */
async function unwrapOrMissing<T extends { status: number; data: unknown }>(request: Promise<T>) {
  try {
    return await unwrap(request);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/**
 * The resolved legal index; shared by the legal pages' navigation and the footer menu. A 404 (an API
 * that does not serve the route yet) reads as an empty index, so every signed-in page does not
 * report a failure for an optional footer entry.
 */
export function useLegalIndex() {
  return useQuery({
    queryKey: ['legal', 'index'],
    queryFn: () => unwrapOrMissing(getLegalIndex()),
    staleTime: FRESH_MS,
  });
}

/** The published copy of `legal-<doc>`; resolves to `null` when it is unknown or unpublished. */
export function usePublishedLegalDocument(doc: string) {
  const slug = slugForDoc(doc);
  return useQuery({
    queryKey: ['legal', 'document', slug],
    queryFn: () => unwrapOrMissing(getPublishedContentDocument(slug ?? '')),
    enabled: slug !== null,
    staleTime: FRESH_MS,
  });
}

/** One page of the document's publications, newest first; keeps the previous page while the next loads. */
export function useLegalHistory(doc: string, page: number) {
  const slug = slugForDoc(doc);
  return useQuery({
    queryKey: ['legal', 'history', slug, page],
    queryFn: () =>
      unwrap(listContentDocumentHistory(slug ?? '', { page, size: HISTORY_PAGE_SIZE })),
    enabled: slug !== null,
    staleTime: FRESH_MS,
    placeholderData: keepPreviousData,
  });
}

/** One earlier publication; resolves to `null` for an unknown, malformed or draft snapshot id. */
export function useLegalPublication(doc: string, id: string) {
  const slug = slugForDoc(doc);
  return useQuery({
    queryKey: ['legal', 'publication', slug, id],
    queryFn: () =>
      unwrapOrMissing(getContentDocumentPublication(slug ?? '', encodeURIComponent(id))),
    enabled: slug !== null,
    staleTime: FRESH_MS,
  });
}
