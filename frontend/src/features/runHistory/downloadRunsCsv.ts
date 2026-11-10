import {
  ApiError,
  authHeaders,
  getExportRunsUrl,
  getRunTray,
  unwrap,
  type ExportRunsParams,
  type ProblemDetail,
} from '@flow-cortex/api-client';
import { activeLocale } from '../../lib/i18n';
import { toListParams, type RunHistoryFilters } from './runHistoryRules';

export interface CsvDownload {
  /** Rows the filters match in all; the file holds at most the server's cap of them. */
  readonly total: number | null;
}

const FALLBACK_FILE_NAME = 'runs.csv';

function exportParams(filters: RunHistoryFilters): ExportRunsParams {
  const { page: _page, size: _size, ...rest } = toListParams(filters, 1);
  return rest;
}

function request(url: string): Promise<Response> {
  const headers = new Headers(authHeaders().headers);
  headers.set('Accept-Language', activeLocale());
  return fetch(url, { headers });
}

/** `filename="runs.csv"` or the RFC 5987 `filename*=UTF-8''…` form; the server's own name wins. */
export function fileNameOf(disposition: string | null): string {
  if (!disposition) return FALLBACK_FILE_NAME;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      // A malformed escape falls through to the plain form.
    }
  }
  return /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? FALLBACK_FILE_NAME;
}

async function problemOf(response: Response): Promise<ProblemDetail | undefined> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

/**
 * Downloads the CSV through an authorized `fetch` — a plain link cannot send the bearer token and
 * the generated client reads every body as JSON. An expired token is renewed by one cheap
 * generated-client call, which shares the client's single refresh, and the download is retried once.
 */
export async function downloadRunsCsv(filters: RunHistoryFilters): Promise<CsvDownload> {
  const url = getExportRunsUrl(exportParams(filters));
  let response = await request(url);
  if (response.status === 401) {
    await unwrap(getRunTray(authHeaders()));
    response = await request(url);
  }
  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  const blob = await response.blob();
  const total = Number(response.headers.get('X-Total-Count'));
  const link = document.createElement('a');
  const objectUrl = URL.createObjectURL(blob);
  link.href = objectUrl;
  link.download = fileNameOf(response.headers.get('Content-Disposition'));
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
  return { total: Number.isFinite(total) && response.headers.has('X-Total-Count') ? total : null };
}
