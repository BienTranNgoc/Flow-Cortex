import {
  authHeaders,
  getAdminSupportRequest,
  listAdminSupportRequests,
  replyToAdminSupportRequest,
  setAdminSupportRequestStatus,
  type AdminSupportRequestDetail,
  type AdminSupportRequestReply,
  type AdminSupportRequestStatusChangeStatus,
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

/** One page of requests, newest activity first; the previous page stays visible while the next loads. */
export function useRequestsQuery(
  page: number,
  status: ListAdminSupportRequestsStatus | undefined,
  type: ListAdminSupportRequestsType | undefined,
) {
  return useQuery({
    queryKey: [...REQUESTS_KEY, 'list', page, status, type],
    queryFn: () =>
      unwrapSupport(
        listAdminSupportRequests(
          { page, ...(status ? { status } : {}), ...(type ? { type } : {}) },
          authHeaders(),
        ),
      ),
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
