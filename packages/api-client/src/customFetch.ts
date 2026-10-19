import { withClientId } from './clientId';
import { withLocale } from './locale';
import { getToken } from './tokenStore';
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
  return toResult<T>(response);
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
