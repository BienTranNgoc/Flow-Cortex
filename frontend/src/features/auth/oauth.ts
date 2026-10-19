import { authCopy } from './authCopy';

/** Where the browser goes to start Google sign-in; the backend answers with a redirect to Google. */
export function googleAuthorizeUrl(rememberMe: boolean, returnTo: string): string {
  const params = new URLSearchParams({ remember_me: String(rememberMe), return_to: returnTo });
  return `/api/v1/auth/oauth/google/authorize?${params}`;
}

const OAUTH_ERROR_MESSAGES = new Map<string, string>(Object.entries(authCopy.oauthErrors));

/** Human message for the `oauth_error` code the backend puts on `/login`; unknown codes read as a failure. */
export function oauthErrorMessage(code: string): string {
  return OAUTH_ERROR_MESSAGES.get(code) ?? authCopy.oauthErrors.failed;
}
