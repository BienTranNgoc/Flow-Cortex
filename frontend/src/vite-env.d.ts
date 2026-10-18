/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OAUTH_ENABLED?: string;
  readonly VITE_ACCOUNT_STATS_ENABLED?: string;
  readonly VITE_PASSWORD_RESET_ENABLED?: string;
}
