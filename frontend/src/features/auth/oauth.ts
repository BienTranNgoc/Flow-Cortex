/** Where the browser goes to start Google sign-in; the backend answers with a redirect to Google. */
export function googleAuthorizeUrl(rememberMe: boolean, returnTo: string): string {
  const params = new URLSearchParams({ remember_me: String(rememberMe), return_to: returnTo });
  return `/api/v1/auth/oauth/google/authorize?${params}`;
}

const FAILED = 'auth.oauthError.failed';

const OAUTH_ERROR_KEYS = new Map([
  ['cancelled', 'auth.oauthError.cancelled'],
  ['invalid_state', 'auth.oauthError.invalidState'],
  ['registration_closed', 'auth.oauthError.registrationClosed'],
  ['link_required', 'auth.oauthError.linkRequired'],
  ['account_inactive', 'auth.oauthError.accountInactive'],
  ['rate_limited', 'auth.oauthError.rateLimited'],
  ['failed', FAILED],
]);

/** Translation key for the `oauth_error` code the backend puts on `/login`; unknown codes read as a failure. */
export function oauthErrorKey(code: string): string {
  return OAUTH_ERROR_KEYS.get(code) ?? FAILED;
}
