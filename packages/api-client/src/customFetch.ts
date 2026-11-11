import { withClientId } from './clientId';
import { withLocale } from './locale';
import { getToken } from './tokenStore';
import type { ProblemDetail } from './problemDetail';
import { REFRESH_PATH, refreshAccessToken } from './session';

/** A 401 from these is an answer, not an expired session — never refreshed or replayed. */
const NO_REFRESH_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/register',
  REFRESH_PATH,
  '/api/v1/auth/logout',
];

/**
 * Fetch used by every generated operation (orval `override.mutator`). A 401 triggers one shared
 * refresh and the request is replayed once with the new token; a replay that is rejected again
 * is returned as is.
 */
export async function customFetch<T>(url: string, options: RequestInit = {}): Promise<T> {
  return toResult<T>(await fetchWithRefresh(url, options));
}

export interface DownloadResult {
  status: number;
  /** The file body on a 2xx answer. */
  blob?: Blob | undefined;
  /** Name from `Content-Disposition` on a 2xx answer, when the server sent one. */
  fileName?: string | undefined;
  /** The parsed `application/problem+json` body on any other answer, when it parses. */
  problem?: ProblemDetail | undefined;
}

/**
 * Downloads a file with the same token refresh and replay as {@link customFetch}. The caller sets
 * `Accept` (and the bearer) in `options`; a non-2xx answer is returned as its problem, never thrown.
 */
export async function downloadFile(url: string, options: RequestInit = {}): Promise<DownloadResult> {
  const response = await fetchWithRefresh(url, options);
  if (response.ok) {
    return {
      status: response.status,
      blob: await response.blob(),
      fileName: fileNameOf(response.headers.get('Content-Disposition')),
    };
  }
  return { status: response.status, problem: await problemOf(response) };
}

async function fetchWithRefresh(url: string, options: RequestInit): Promise<Response> {
  const sentToken = bearerOf(options.headers);
  let response = await fetch(url, {
    ...options,
    headers: withLocale(withClientId(url, options.headers)),
  });
  if (response.status === 401 && !NO_REFRESH_PATHS.includes(url.split('?')[0] ?? url)) {
    const current = getToken();
    const token = current !== null && current !== sentToken ? current : await refreshAccessToken();
    if (token !== null) {
      response = await fetch(url, withBearer(options, token));
    }
  }
  return response;
}

function fileNameOf(disposition: string | null): string | undefined {
  if (disposition === null) return undefined;
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (encoded !== undefined) {
    try {
      return decodeURIComponent(encoded.trim());
    } catch {
      // a malformed escape falls back to the plain filename
    }
  }
  const plain = /filename\s*=\s*(?:"([^"]*)"|([^;]+))/i.exec(disposition);
  return (plain?.[1] ?? plain?.[2])?.trim() || undefined;
}

async function problemOf(response: Response): Promise<ProblemDetail | undefined> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as ProblemDetail;
  } catch {
    return undefined;
  }
}

function bearerOf(headers: HeadersInit | undefined): string | null {
  return new Headers(headers).get('Authorization')?.replace(/^Bearer /, '') ?? null;
}

function withBearer(options: RequestInit, token: string): RequestInit {
  const headers = withLocale(options.headers);
  headers.set('Authorization', `Bearer ${token}`);
  return { ...options, headers };
}

async function toResult<T>(res: Response): Promise<T> {
  const body = [204, 205, 304].includes(res.status) ? null : await res.text();
  const data = body === null ? undefined : body ? JSON.parse(body) : {};
  return { data, status: res.status, headers: res.headers } as T;
}
