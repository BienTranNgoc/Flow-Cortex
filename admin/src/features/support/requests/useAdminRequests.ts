import {
  assignAdminSupportRequest,
  authHeaders,
  bulkAssignAdminSupportRequests,
  getAdminSupportRequest,
  getAdminSupportRequestFacets,
  listAdminSupportRequests,
  replyToAdminSupportRequest,
  setAdminSupportRequestStatus,
  type AdminSupportRequestDetail,
  type AdminSupportRequestReply,
  type AdminSupportRequestStatusChangeStatus,
  type ListAdminSupportRequestsCreated,
  type ListAdminSupportRequestsParams,
  type ListAdminSupportRequestsSort,
  type ListAdminSupportRequestsStatus,
  type ListAdminSupportRequestsType,
} from '@flow-cortex/api-client';
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import { useMemo, useRef } from 'react';
import { supportProblemCode, unwrapSupport } from '../unwrapSupport';
import { uploadAttachments } from './attachments';

export const PAGE_SIZE = 20;

const REQUESTS_KEY = ['admin', 'support', 'requests'] as const;
const DETAIL = 'detail';
const detailKey = (number: number) => [...REQUESTS_KEY, DETAIL, number] as const;

/** The inbox filter as the URL carries it; an absent key means no filter on that dimension. */
export interface RequestFilter {
  status?: ListAdminSupportRequestsStatus | undefined;
  type?: ListAdminSupportRequestsType | undefined;
  /** `me`, `unassigned` or an account id. */
  assignee?: string | undefined;
  created?: ListAdminSupportRequestsCreated | undefined;
  q?: string | undefined;
  sort?: ListAdminSupportRequestsSort | undefined;
  page: number;
}

/** Drops the absent keys: the generated params type rejects an explicit `undefined`. */
function definedOnly<T extends object>(params: T): { [K in keyof T]?: Exclude<T[K], undefined> } {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined),
  ) as { [K in keyof T]?: Exclude<T[K], undefined> };
}

/** One page of requests; the previous page stays visible while the next loads. */
export function useRequestsQuery(filter: RequestFilter) {
  const params: ListAdminSupportRequestsParams = definedOnly({ ...filter });
  return useQuery({
    queryKey: [...REQUESTS_KEY, 'list', params],
    queryFn: () => unwrapSupport(listAdminSupportRequests(params, authHeaders())),
    placeholderData: keepPreviousData,
  });
}

/** Counts per status, type, created bucket and assignee, each ignoring its own selection. */
export function useRequestFacets(filter: RequestFilter) {
  const { page: _page, sort: _sort, ...rest } = filter;
  const params = definedOnly(rest);
  return useQuery({
    queryKey: [...REQUESTS_KEY, 'facets', params],
    queryFn: () => unwrapSupport(getAdminSupportRequestFacets(params, authHeaders())),
    placeholderData: keepPreviousData,
  });
}

/** Always read fresh on open: the status may have changed and attachment URLs expire. */
export function useRequestDetail(number: number) {
  return useQuery({
    queryKey: detailKey(number),
    queryFn: () => unwrapSupport(getAdminSupportRequest(number, authHeaders())),
    enabled: Number.isSafeInteger(number) && number > 0,
    refetchOnMount: 'always',
  });
}

/** Stores the answered request and marks every list and the open count stale, the detail excepted. */
function showDetail(queryClient: QueryClient, detail: AdminSupportRequestDetail) {
  queryClient.setQueryData(detailKey(detail.number), detail);
  invalidateLists(queryClient);
}

function invalidateLists(queryClient: QueryClient) {
  void queryClient.invalidateQueries({
    queryKey: REQUESTS_KEY,
    predicate: (query) => query.queryKey[REQUESTS_KEY.length] !== DETAIL,
  });
}

/** Refusals meaning another actor changed the request first, so the inbox and open count are stale too. */
const LOST_RACE = new Set([
  'request-state-changed',
  'transition-not-allowed',
  'request-closed',
  'requester-deleted',
  'idempotency-key-reused',
]);

function onRefused(queryClient: QueryClient, error: unknown) {
  const code = supportProblemCode(error);
  if (code && LOST_RACE.has(code)) invalidateLists(queryClient);
}

export function useSetStatus(number: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (status: AdminSupportRequestStatusChangeStatus) =>
      unwrapSupport(setAdminSupportRequestStatus(number, { status }, authHeaders())),
    onSuccess: (detail) => showDetail(queryClient, detail),
    onError: (error) => onRefused(queryClient, error),
  });
}

/** Sets or clears (`undefined`) the assignee; assigning a new request picks it up into in progress. */
export function useAssignRequest(number: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (assigneeId: string | undefined) =>
      unwrapSupport(
        assignAdminSupportRequest(number, assigneeId ? { assigneeId } : {}, authHeaders()),
      ),
    onSuccess: (detail) => showDetail(queryClient, detail),
    onError: (error) => onRefused(queryClient, error),
  });
}

export function useBulkAssign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ numbers, assigneeId }: { numbers: number[]; assigneeId: string }) =>
      unwrapSupport(bulkAssignAdminSupportRequests({ numbers, assigneeId }, authHeaders())),
    // Each row may be assigned, refused or changed by someone else: every list is stale either way.
    onSettled: () => invalidateLists(queryClient),
  });
}

function withKey(key: string): RequestInit {
  const auth = authHeaders().headers as Record<string, string> | undefined;
  return { headers: { ...auth, 'Idempotency-Key': key } };
}

export function useReply(number: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: AdminSupportRequestReply; key: string }) =>
      unwrapSupport(replyToAdminSupportRequest(number, body, withKey(key))),
    onSuccess: (detail) => showDetail(queryClient, detail),
    onError: (error) => onRefused(queryClient, error),
  });
}

/** One idempotency key per submit: the same key until `reset`, so a retry of the same submit is stored once. */
export function useSubmitKey() {
  const key = useRef<string | null>(null);
  return useMemo(
    () => ({
      current: () => (key.current ??= crypto.randomUUID()),
      reset: () => {
        key.current = null;
      },
    }),
    [],
  );
}

/** Uploads `files` once per file list: a retry of the same submit reuses the ids already granted. */
export function useUploadedIds() {
  const done = useRef<{ files: File[]; ids: string[] } | null>(null);
  return async (files: File[]) => {
    const cached = done.current;
    if (cached?.files.length === files.length && cached.files.every((f, i) => f === files[i])) {
      return cached.ids;
    }
    const ids = await uploadAttachments(files);
    done.current = { files, ids };
    return ids;
  };
}
