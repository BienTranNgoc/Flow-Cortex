// OAuth entry point. Only Google has a backend flow: it is a link to the
// authorize endpoint (`href`). A provider without `href` stays inert.
//
// Hidden by default. Set `VITE_OAUTH_ENABLED=true` to surface the button.

import { oauthEnabled } from './featureFlags';

export interface OAuthButtonProps {
  readonly provider: 'google' | 'microsoft';
  readonly label: string;
  readonly href?: string;
  readonly onSelect?: (provider: OAuthButtonProps['provider']) => void;
}

const ICONS: Record<OAuthButtonProps['provider'], React.ReactNode> = {
  google: (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 48 48"
      aria-hidden="true"
      focusable="false"
    >
      <path
        fill="#FFC107"
        d="M43.6 20.5H42V20H24v8h11.3c-1.6 4.6-6 8-11.3 8-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34 5.7 29.2 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.2-.1-2.3-.4-3.5z"
      />
      <path
        fill="#FF3D00"
        d="M6.3 14.7l6.6 4.8C14.7 16 19 13 24 13c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34 5.7 29.2 4 24 4 16.3 4 9.6 8.4 6.3 14.7z"
      />
      <path
        fill="#4CAF50"
        d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2c-2 1.4-4.5 2.4-7.2 2.4-5.3 0-9.7-3.4-11.3-8L6.2 33C9.4 39.6 16.2 44 24 44z"
      />
      <path
        fill="#1976D2"
        d="M43.6 20.5H42V20H24v8h11.3c-.7 2-2 3.8-3.7 5.1l6.2 5.2c-.4.4 6.2-4.5 6.2-14.3 0-1.2-.1-2.3-.4-3.5z"
      />
    </svg>
  ),
  microsoft: (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <path fill="#F25022" d="M2 2h9.5v9.5H2z" />
      <path fill="#7FBA00" d="M12.5 2H22v9.5h-9.5z" />
      <path fill="#00A4EF" d="M2 12.5h9.5V22H2z" />
      <path fill="#FFB900" d="M12.5 12.5H22V22h-9.5z" />
    </svg>
  ),
};

export function OAuthButton({ provider, label, href, onSelect }: OAuthButtonProps) {
  if (!oauthEnabled()) {
    return null;
  }
  const className = `oauth-button oauth-button--${provider}`;
  const content = (
    <>
      <span className="oauth-button__icon" aria-hidden="true">
        {ICONS[provider]}
      </span>
      <span className="oauth-button__label">{label}</span>
    </>
  );
  if (href) {
    return (
      <a className={className} href={href}>
        {content}
      </a>
    );
  }
  return (
    <button type="button" className={className} onClick={() => onSelect?.(provider)}>
      {content}
    </button>
  );
}
