/** Where the browser goes to start Google sign-in; the backend answers with a redirect to Google. */
export function googleAuthorizeUrl(rememberMe: boolean, returnTo: string): string {
  const params = new URLSearchParams({ remember_me: String(rememberMe), return_to: returnTo });
  return `/api/v1/auth/oauth/google/authorize?${params}`;
}

const FAILED = 'Google sign-in failed. Please try again.';

const OAUTH_ERROR_MESSAGES = new Map([
  ['cancelled', 'Google sign-in was cancelled.'],
  ['invalid_state', 'The Google sign-in expired or was invalid. Please try again.'],
  ['registration_closed', 'Registration is closed, so a new account cannot be created with Google.'],
  ['link_required', 'This email already has a password account — sign in with your password.'],
  ['account_inactive', 'This account is not active. Please contact support.'],
  ['rate_limited', 'Too many attempts. Please try again later.'],
  ['failed', FAILED],
]);

/** Human message for the `oauth_error` code the backend puts on `/login`; unknown codes read as a failure. */
export function oauthErrorMessage(code: string): string {
  return OAUTH_ERROR_MESSAGES.get(code) ?? FAILED;
}
