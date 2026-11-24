import {
  ApiError,
  authHeaders,
  createDriveItem,
  deleteDriveItem,
  listDriveItems,
  listDriveSuggestions,
  unwrap,
  updateDriveItem,
  type CreateDriveItemRequest,
  type UpdateDriveItemRequest,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { untilLinkRenewal } from '../../../lib/signedLinks';
import type { ContentType } from '../model/driveRoles';
import { uploadDriveItem, type UploadDriveItemInput } from './uploadDriveItem';

export const ITEMS_PAGE_SIZE = 20;
const SUGGESTIONS_LIMIT = 20;

function invalidateItems(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['drive', 'items'] }),
    queryClient.invalidateQueries({ queryKey: ['drive', 'suggestions'] }),
  ]);
}

/** A 404 means the item is gone on the server, so the lists must reload to say so. */
function invalidateWhenGone(queryClient: QueryClient, error: unknown) {
  return error instanceof ApiError && error.status === 404 ? invalidateItems(queryClient) : undefined;
}

/** One page of the member's items of one content type, with its signed links renewed before they expire. */
export function useDriveItemsQuery(contentType: ContentType, page: number) {
  return useQuery({
    queryKey: ['drive', 'items', contentType, page],
    queryFn: () =>
      unwrap(listDriveItems({ contentType, page, size: ITEMS_PAGE_SIZE }, authHeaders())),
    // The previous page stays on screen while the next loads, but never another content type's.
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === contentType ? previous : undefined,
    staleTime: (query) => untilLinkRenewal(query.state.data?.items.map((item) => item.urlExpiresAt) ?? []),
    refetchInterval: (query) =>
      untilLinkRenewal(query.state.data?.items.map((item) => item.urlExpiresAt) ?? []) || false,
  });
}

/** Candidates from the member's runs worth keeping, with their signed links renewed before they expire. */
export function useDriveSuggestionsQuery() {
  return useQuery({
    queryKey: ['drive', 'suggestions'],
    queryFn: () => unwrap(listDriveSuggestions({ limit: SUGGESTIONS_LIMIT }, authHeaders())),
    staleTime: (query) => untilLinkRenewal(query.state.data?.map((candidate) => candidate.urlExpiresAt) ?? []),
    refetchInterval: (query) =>
      untilLinkRenewal(query.state.data?.map((candidate) => candidate.urlExpiresAt) ?? []) || false,
  });
}

export function useCreateDriveItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateDriveItemRequest) => unwrap(createDriveItem(body, authHeaders())),
    onSuccess: () => invalidateItems(queryClient),
  });
}

/** The upload, its create and the reload of the lists as one mutation. */
export function useUploadDriveItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: UploadDriveItemInput) => uploadDriveItem(input),
    onSuccess: () => invalidateItems(queryClient),
  });
}

export function useUpdateDriveItem(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateDriveItemRequest) => unwrap(updateDriveItem(id, body, authHeaders())),
    onSuccess: () => invalidateItems(queryClient),
    onError: (error) => invalidateWhenGone(queryClient, error),
  });
}

export function useDeleteDriveItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap(deleteDriveItem(id, authHeaders())),
    onSuccess: () => invalidateItems(queryClient),
    onError: (error) => invalidateWhenGone(queryClient, error),
  });
}
