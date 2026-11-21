import { getToken, setToken } from './tokenStore';
import { ApiError, type ProblemDetail } from './problemDetail';

/** Bearer-auth `RequestInit` for the generated client's `options` param. */
export function authHeaders(): RequestInit {
  const token = getToken();
  return token ? { headers: { Authorization: `Bearer ${token}` } } : {};
}

/**
 * Unwraps an orval-generated `{ data, status, headers }` response: returns `data` on 2xx,
 * throws {@link ApiError} otherwise — the one place a non-2xx becomes an error TanStack Query
 * recognises (the generated client never throws on its own).
 *
 * <p>On a 401 the stored token is cleared: `RequireAuth` reacts on its next render and
 * redirects to `/login` — an expired/revoked token behaves like no token at all.
 */
export async function unwrap<T extends { status: number; data: unknown }>(
  promise: Promise<T>,
): Promise<SuccessOf<T>['data']> {
  const response = await promise;
  if (response.status >= 400) {
    if (response.status === 401) {
      setToken(null);
    }
    // The generated response type models only the SUCCESS shape (e.g.
    // `LoginResponse`); on an error path `data` is really the problem+json
    // body, hence the explicit re-cast.
    const problem = response.data as unknown as ProblemDetail;
    throw new ApiError(response.status, problem);
  }
  return response.data as SuccessOf<T>['data'];
}

type SuccessStatus = 200 | 201 | 202 | 203 | 204 | 205 | 206;

/** The 2xx members of a generated response union, so documented error responses never widen `data`. */
type SuccessOf<T extends { status: number }> = [Extract<T, { status: SuccessStatus }>] extends [never]
  ? T
  : Extract<T, { status: SuccessStatus }>;
