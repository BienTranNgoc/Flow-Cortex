/** Names the calling app to the backend, which keeps one refresh cookie per app. */
export const CLIENT_HEADER = 'X-FlowCortex-Client';

/** The calls that read or write the refresh cookie; every other call is left unmarked. */
const COOKIE_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/register',
  '/api/v1/auth/refresh',
  '/api/v1/auth/logout',
];

let clientId: string | null = null;

/** Set once at app startup; an app that never calls it is the default (member) client. */
export function setClientId(id: string | null): void {
  clientId = id;
}

/** `headers` plus the client mark, when a client id is set and `url` is a cookie-bearing auth call. */
export function withClientId(url: string, headers?: HeadersInit): HeadersInit {
  const marked = new Headers(headers);
  if (clientId !== null && COOKIE_PATHS.includes(url.split('?')[0] ?? url)) {
    marked.set(CLIENT_HEADER, clientId);
  }
  return marked;
}
