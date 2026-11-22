import {
  authHeaders,
  getContentDocumentDraft,
  getContentDocumentRevision,
  listContentDocumentRevisions,
  listContentDocuments,
  publishContentDocument,
  saveContentDocumentDraft,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapContent } from './contentProblems';
import type { DocumentContent } from './documentModel';

export const REVISIONS_PAGE_SIZE = 20;

const ROOT = ['admin', 'content', 'documents'] as const;
const LIST_KEY = [...ROOT, 'list'] as const;
const draftKey = (slug: string) => [...ROOT, slug, 'draft'] as const;
const revisionsKey = (slug: string) => [...ROOT, slug, 'revisions'] as const;

const readDraft = (slug: string) => unwrapContent(getContentDocumentDraft(slug, authHeaders()));

/** Every registered document with its draft and published versions, in registration order. */
export function useDocumentsQuery() {
  return useQuery({
    queryKey: LIST_KEY,
    queryFn: () => unwrapContent(listContentDocuments(authHeaders())),
  });
}

export function useDraftQuery(slug: string) {
  return useQuery({ queryKey: draftKey(slug), queryFn: () => readDraft(slug) });
}

export function useRevisionsQuery(slug: string, page: number) {
  return useQuery({
    queryKey: [...revisionsKey(slug), page],
    queryFn: () =>
      unwrapContent(
        listContentDocumentRevisions(slug, { page, size: REVISIONS_PAGE_SIZE }, authHeaders()),
      ),
    placeholderData: keepPreviousData,
  });
}

/** Stores the draft the server answered and refreshes what a save changes: the list and the snapshots. */
export function useSaveDraftMutation(slug: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ version, content }: { version: number; content: DocumentContent }) =>
      unwrapContent(saveContentDocumentDraft(slug, { version, ...content }, authHeaders())),
    onSuccess: (draft) => {
      queryClient.setQueryData(draftKey(slug), draft);
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
      void queryClient.invalidateQueries({ queryKey: revisionsKey(slug) });
    },
  });
}

/** The draft version published: the snapshot list and the list's published columns move. */
export function usePublishMutation(slug: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (version: number) =>
      unwrapContent(publishContentDocument(slug, { version }, authHeaders())),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: draftKey(slug) });
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
      void queryClient.invalidateQueries({ queryKey: revisionsKey(slug) });
    },
  });
}

/** Reads the stored draft past the cache, for "Load latest" after a 409. */
export function useLatestDraft(slug: string) {
  const queryClient = useQueryClient();
  return () =>
    queryClient.fetchQuery({
      queryKey: draftKey(slug),
      queryFn: () => readDraft(slug),
      staleTime: 0,
    });
}

/** Reads one snapshot, for "Load into editor". */
export function readRevision(slug: string, id: string) {
  return unwrapContent(getContentDocumentRevision(slug, id, authHeaders()));
}
