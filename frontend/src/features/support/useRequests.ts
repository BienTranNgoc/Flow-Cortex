import {
  ApiError,
  authHeaders,
  cancelSupportRequest,
  confirmSupportRequest,
  createSupportRequest,
  getSupportRequest,
  listSupportRequests,
  replyToSupportRequest,
  type SupportRequestCreate,
  type SupportRequestDetail,
  type SupportRequestReply,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useMemo, useRef } from 'react';
import { UploadFailedError } from '../workflow/uploadFile';
import { uploadAttachments } from './attachments';
import { supportCopy } from './supportCopy';
import { DETAIL_KEY, LIST_KEY, UNREAD_KEY } from './useUnread';
import { supportProblemCode, unwrapSupport } from './useSupportKb';
import { newUuidV4 } from './uuid';

const detailKey = (number: number) => [...DETAIL_KEY, number] as const;

export type RequestStatus = SupportRequestDetail['status'];

/** What a member may do to a request in each status. */
export const ALLOWED_ACTIONS: Record<RequestStatus, { reply: boolean; cancel: boolean; confirm: boolean }> = {
  new: { reply: true, cancel: true, confirm: false },
  in_progress: { reply: true, cancel: true, confirm: false },
  waiting_member: { reply: true, cancel: true, confirm: false },
  resolved: { reply: true, cancel: false, confirm: true },
  closed: { reply: false, cancel: false, confirm: false },
};

/** Length in code points after trimming, the way the server counts. */
export function textLength(value: string): number {
  return [...value.trim()].length;
}

export function isSuspended(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

export interface Failure {
  message: string;
  /** The request changed elsewhere: offer to load it again. */
  reload: boolean;
  /** Field name → server message, on `validation-failed`. */
  fields: Record<string, string>;
}

function problemMessage(code: string | undefined): string | undefined {
  const value: unknown = code ? (supportCopy.problems as Record<string, unknown>)[code] : undefined;
  return typeof value === 'string' ? value : undefined;
}

/** The message for a failed submit or action; the server's own text only for a refused attachment. */
export function failureOf(error: unknown): Failure {
  if (error instanceof UploadFailedError) {
    return { message: supportCopy.requests.attachUploadFailed, reload: false, fields: {} };
  }
  const code = supportProblemCode(error);
  const problem = error instanceof ApiError ? error.problem : undefined;
  if (code === 'attachment-rejected') {
    return {
      message: problem?.detail ?? supportCopy.problems['attachment-rejected'],
      reload: false,
      fields: {},
    };
  }
  if (code === 'validation-failed') {
    return { message: supportCopy.requests.submitFailed, reload: false, fields: problem?.errors ?? {} };
  }
  return {
    message: problemMessage(code) ?? supportCopy.requests.submitFailed,
    reload: code === 'request-state-changed' || code === 'idempotency-key-reused',
    fields: {},
  };
}

/** One idempotency key per submit: the same key until `reset`, so a retry of the same submit is stored once. */
export function useSubmitKey() {
  const key = useRef<string | null>(null);
  return useMemo(
    () => ({
      current: () => (key.current ??= newUuidV4()),
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

function withKey(key: string): RequestInit {
  const auth = authHeaders().headers as Record<string, string> | undefined;
  return { headers: { ...auth, 'Idempotency-Key': key } };
}

function showDetail(queryClient: QueryClient, detail: SupportRequestDetail) {
  queryClient.setQueryData(detailKey(detail.number), detail);
  void queryClient.invalidateQueries({ queryKey: LIST_KEY });
}

/** A request closed elsewhere is read again, so its thread turns read-only. */
function reloadIfClosed(queryClient: QueryClient, number: number, error: unknown) {
  if (supportProblemCode(error) === 'request-closed') {
    void queryClient.invalidateQueries({ queryKey: detailKey(number) });
  }
}

/** Reads request `number` again (the reload offered after `request-state-changed`). */
export function useReloadRequest(number: number) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: detailKey(number) });
}

export function useRequestList() {
  return useQuery({
    queryKey: LIST_KEY,
    queryFn: () => unwrapSupport(listSupportRequests(authHeaders())),
  });
}

/** Always read fresh: the status may have changed and attachment URLs expire. Reading clears the unread mark. */
export function useRequestDetail(number: number) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: detailKey(number),
    queryFn: async () => {
      const detail = await unwrapSupport(getSupportRequest(number, authHeaders()));
      void queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
      return detail;
    },
    refetchOnMount: 'always',
  });
}

export function useCreateRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: SupportRequestCreate; key: string }) =>
      unwrapSupport(createSupportRequest(body, withKey(key))),
    onSuccess: (detail) => showDetail(queryClient, detail),
  });
}

export function useReply(number: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: SupportRequestReply; key: string }) =>
      unwrapSupport(replyToSupportRequest(number, body, withKey(key))),
    onSuccess: (detail) => showDetail(queryClient, detail),
    onError: (error) => reloadIfClosed(queryClient, number, error),
  });
}

export function useRequestAction(number: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (action: 'cancel' | 'confirm') =>
      unwrapSupport(
        action === 'cancel'
          ? cancelSupportRequest(number, authHeaders())
          : confirmSupportRequest(number, authHeaders()),
      ),
    onSuccess: (detail) => showDetail(queryClient, detail),
    onError: (error) => reloadIfClosed(queryClient, number, error),
  });
}
