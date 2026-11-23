import {
  authHeaders,
  getDriveMemoryText,
  hideDriveMemory,
  listDriveMemory,
  pinDriveMemory,
  unpinDriveMemory,
  unwrap,
} from '@flow-cortex/api-client';
import type { ListDriveMemoryParams, MemoryListResponse } from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { untilLinkRenewal } from '../../../lib/signedLinks';

function untilMemoryRenewal(data: MemoryListResponse | undefined): number {
  return untilLinkRenewal([...(data?.items ?? []), ...(data?.pinned ?? [])].map((entry) => entry.urlExpiresAt));
}

/**
 * One page of the member's Memory with the pinned strip, keeping the previous page on screen while
 * the next loads; signed links are renewed before the first expires. `null` params hold the read.
 */
export function useMemoryQuery(params: ListDriveMemoryParams | null) {
  return useQuery({
    queryKey: ['drive', 'memory', params],
    queryFn: () => unwrap(listDriveMemory(params ?? undefined, authHeaders())),
    enabled: params !== null,
    placeholderData: keepPreviousData,
    staleTime: (query) => untilMemoryRenewal(query.state.data),
    refetchInterval: (query) => untilMemoryRenewal(query.state.data) || false,
  });
}

/** The full text of one text result; held back until an id is given (the dialog is open). */
export function useMemoryText(id: string | undefined) {
  return useQuery({
    queryKey: ['drive', 'memory-text', id],
    queryFn: () => unwrap(getDriveMemoryText(id as string, authHeaders())),
    enabled: Boolean(id),
  });
}

function useMemoryAction(action: (id: string) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: action,
    // Settled, not only successful: a 404 means the result changed elsewhere and the list is stale.
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['drive', 'memory'] }),
  });
}

export function usePinMemory() {
  return useMemoryAction((id) => unwrap(pinDriveMemory(id, authHeaders())));
}

export function useUnpinMemory() {
  return useMemoryAction((id) => unwrap(unpinDriveMemory(id, authHeaders())));
}

export function useHideMemory() {
  return useMemoryAction((id) => unwrap(hideDriveMemory(id, authHeaders())));
}
