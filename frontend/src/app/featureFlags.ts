/** Build-time UI flags, off unless set to `true`; read per call so tests can stub them. */

/** Demo only: XP/streak/rank and account deletion are placeholders — never enable in production. */
export const accountStatsEnabled = (): boolean =>
  import.meta.env.VITE_ACCOUNT_STATS_ENABLED === 'true';

/** Demo only: the runs list and integrations pages show placeholder data until their APIs exist — never enable in production. */
export const demoPagesEnabled = (): boolean => import.meta.env.VITE_DEMO_PAGES_ENABLED === 'true';

/** "Continue with Google" entry; needs the backend OAuth flow configured. */
export const oauthEnabled = (): boolean => import.meta.env.VITE_OAUTH_ENABLED === 'true';

/** Keep off until the app has a `/forgot-password` route and the backend reset endpoint. */
export const passwordResetEnabled = (): boolean =>
  import.meta.env.VITE_PASSWORD_RESET_ENABLED === 'true';
