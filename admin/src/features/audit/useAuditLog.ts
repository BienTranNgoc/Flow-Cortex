import { useInfiniteQuery, useMutation, useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  downloadFile,
  getAuditLogFacets,
  getExportAuditLogUrl,
  listAuditLog,
  unwrap,
} from '@flow-cortex/api-client';
import {
  AUDIT_PAGE_SIZE,
  rangeIsReversed,
  toRequestParams,
  type AuditFilters,
} from './auditFilters';

const AUDIT_QUERY_KEY = ['auditLog'] as const;
const FACETS_STALE_MS = 5 * 60_000;
const EXPORT_FALLBACK_NAME = 'audit-log.csv';

/** Audit rows, newest first, 50 per page; each page after the first follows the previous page's `nextCursor`. */
export function useAuditLogQuery(filters: AuditFilters) {
  const params = toRequestParams(filters);
  return useInfiniteQuery({
    queryKey: [...AUDIT_QUERY_KEY, params],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        listAuditLog(
          { ...params, size: AUDIT_PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) },
          authHeaders(),
        ),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    enabled: !rangeIsReversed(filters),
  });
}

/** The action and resource-type values the filter selects offer, read from the running server. */
export function useAuditFacetsQuery() {
  return useQuery({
    queryKey: ['auditLogFacets'],
    queryFn: () => unwrap(getAuditLogFacets(authHeaders())),
    staleTime: FACETS_STALE_MS,
  });
}

function saveBlob(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

/** Downloads the CSV for the current filters; a refusal (422, 503, 403) is thrown as its {@link ApiError}. */
export function useExportAuditLog() {
  return useMutation({
    mutationFn: async (filters: AuditFilters) => {
      const result = await downloadFile(getExportAuditLogUrl(toRequestParams(filters)), {
        headers: {
          ...(authHeaders().headers as Record<string, string> | undefined),
          Accept: 'text/csv, application/problem+json',
        },
      });
      if (result.blob === undefined) {
        throw new ApiError(result.status, result.problem);
      }
      const fileName = result.fileName ?? EXPORT_FALLBACK_NAME;
      saveBlob(result.blob, fileName);
      return fileName;
    },
  });
}
