import type { CheckOAuthAuthorizationParams, CheckResponse } from '@flow-cortex/api-client';

export const CONSENT_PATH = '/oauth/authorize';

const CONSENT_QUERY_KEY = 'fc.oauthConsent.query';

/** The part of `Storage` the consent page uses, so a test can hand in a stand-in. */
export type ConsentStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** The tab's session storage; reached inside each call so a blocked storage throws where it is caught. */
export const tabStorage: ConsentStorage = {
  getItem: (key) => window.sessionStorage.getItem(key),
  setItem: (key, value) => window.sessionStorage.setItem(key, value),
  removeItem: (key) => window.sessionStorage.removeItem(key),
};

/** Where the browser goes to hand the client an OAuth error, appended to the checked redirect URI. */
export function consentRedirect(
  redirectUri: string,
  params: { error: string; state?: string | null | undefined },
): string {
  const pairs = [`error=${encodeURIComponent(params.error)}`];
  if (params.state) pairs.push(`state=${encodeURIComponent(params.state)}`);
  const separator = /[?&]$/.test(redirectUri) ? '' : redirectUri.includes('?') ? '&' : '?';
  return `${redirectUri}${separator}${pairs.join('&')}`;
}

export type ConsentCheckInput =
  | { phase: 'pending' }
  | { phase: 'answered'; check: CheckResponse }
  | { phase: 'failed'; status: number | undefined };

export type ConsentView =
  'loading' | 'invalidLink' | 'unavailable' | 'failed' | 'redirectError' | 'notEligible' | 'ready';

/**
 * Which state of the page a check answer leads to. A request with a redirectable fault goes back
 * to the client whoever looks at it, so that outranks the account not being eligible.
 */
export function consentView(input: ConsentCheckInput): ConsentView {
  if (input.phase === 'pending') return 'loading';
  if (input.phase === 'failed') {
    if (input.status === 400) return 'invalidLink';
    if (input.status === 503) return 'unavailable';
    return 'failed';
  }
  if (input.check.error) return 'redirectError';
  return input.check.eligible ? 'ready' : 'notEligible';
}

const AUTHORIZE_FIELDS = [
  'response_type',
  'client_id',
  'redirect_uri',
  'code_challenge',
  'code_challenge_method',
  'scope',
  'state',
  'resource',
] as const;

/** The authorization request of the page URL; anything else on it (a `client_name`) is dropped. */
export function authorizeParams(search: URLSearchParams): CheckOAuthAuthorizationParams {
  const params: CheckOAuthAuthorizationParams = {};
  for (const field of AUTHORIZE_FIELDS) {
    const value = search.get(field);
    if (value !== null) params[field] = value;
  }
  return params;
}

export type GrantFailure =
  'stepUp' | 'notEligible' | 'limitReached' | 'invalidLink' | 'unavailable' | 'failed';

/** What a refused grant means for the page, from its status and problem code. */
export function grantFailure(status: number, code: string | undefined): GrantFailure {
  if (status === 403 && code === 'STEP_UP_REQUIRED') return 'stepUp';
  if (status === 403 && code === 'PAT_NOT_ELIGIBLE') return 'notEligible';
  if (status === 409 && code === 'PAT_LIMIT_REACHED') return 'limitReached';
  if (status === 400) return 'invalidLink';
  if (status === 503) return 'unavailable';
  return 'failed';
}

/**
 * Keeps the query of a consent request in the tab while the visitor signs in: Google sign-in
 * carries only a short return path, so a long query cannot travel with it.
 */
export function rememberConsentQuery(storage: ConsentStorage, search: string): void {
  if (search === '') return;
  try {
    storage.setItem(CONSENT_QUERY_KEY, search);
  } catch {
    // Storage is blocked: the visitor re-opens the link from the client instead.
  }
}

/**
 * The remembered query for a page opened without one, once; a page opened with its own query
 * wins and drops what was remembered.
 */
export function restoreConsentQuery(storage: ConsentStorage, search: string): string | null {
  try {
    const stored = storage.getItem(CONSENT_QUERY_KEY);
    storage.removeItem(CONSENT_QUERY_KEY);
    return search === '' && stored ? stored : null;
  } catch {
    return null;
  }
}
