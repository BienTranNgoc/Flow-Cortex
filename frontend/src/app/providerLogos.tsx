interface LogoProps {
  readonly size?: number;
  readonly title?: string;
}

const wrap = (size: number, title: string, children: React.ReactNode) => (
  <svg
    viewBox="0 0 24 24"
    width={size}
    height={size}
    fill="none"
    stroke="currentColor"
    strokeWidth="1.6"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-label={title}
    role="img"
  >
    {children}
  </svg>
);

export function OpenAILogo({ size = 22, title = 'OpenAI' }: LogoProps) {
  return (
    <span style={{ color: '#10A37F' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M9 9.5a3 3 0 1 1 4.5 2.6c-.7.4-1.5 1-1.5 1.9v.5" />
          <circle cx="12" cy="17" r="0.6" fill="currentColor" />
        </>,
      )}
    </span>
  );
}

export function AnthropicLogo({ size = 22, title = 'Anthropic' }: LogoProps) {
  return (
    <span style={{ color: '#D97757' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <path d="M8 4l-5 16h3l1-3.5h6L14 20h3L12 4z" />
          <path d="M9.5 13l2-6 2 6" />
        </>,
      )}
    </span>
  );
}

export function GoogleLogo({ size = 22, title = 'Google' }: LogoProps) {
  return (
    <span style={{ color: '#4285F4' }} className="integrations-page__logo integrations-page__logo--multi">
      <svg viewBox="0 0 24 24" width={size} height={size} aria-label={title} role="img">
        <path fill="#4285F4" d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4c-.2 1.3-1 2.4-2 3.1v2.6h3.3c1.9-1.7 2.9-4.3 2.9-7.5z" />
        <path fill="#34A853" d="M12 22c2.7 0 5-1 6.6-2.4l-3.3-2.6c-.9.6-2 1-3.3 1-2.6 0-4.7-1.7-5.5-4.1H3.1v2.6C4.7 19.6 8.1 22 12 22z" />
        <path fill="#FBBC05" d="M6.5 13.9a6 6 0 0 1 0-3.8V7.5H3.1a10 10 0 0 0 0 9l3.4-2.6z" />
        <path fill="#EA4335" d="M12 6.1c1.5 0 2.8.5 3.8 1.5l2.9-2.9C16.9 3.1 14.7 2 12 2 8.1 2 4.7 4.4 3.1 7.5l3.4 2.6c.8-2.4 2.9-4 5.5-4z" />
      </svg>
    </span>
  );
}

export function MistralLogo({ size = 22, title = 'Mistral' }: LogoProps) {
  return (
    <span style={{ color: '#FF7000' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <path d="M5 4v16" />
          <path d="M9 4l3 8 3-8" />
          <path d="M12 12v8" />
          <path d="M16 4l3 16" />
        </>,
      )}
    </span>
  );
}

export function RunwayLogo({ size = 22, title = 'Runway' }: LogoProps) {
  return (
    <span style={{ color: '#0EE1B1' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <path d="M3 19V5h11" />
          <path d="M14 5h7v14h-7" />
        </>,
      )}
    </span>
  );
}

export function LumaLogo({ size = 22, title = 'Luma' }: LogoProps) {
  return (
    <span style={{ color: '#7C5CFF' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <circle cx="12" cy="12" r="7" />
          <path d="M9 9l6 6M9 15l6-6" />
        </>,
      )}
    </span>
  );
}

export function KlingLogo({ size = 22, title = 'Kling' }: LogoProps) {
  return (
    <span style={{ color: '#FF4D4D' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <polygon points="12,3 21,8 17,20 7,20 3,8" />
          <path d="M9 12l2 3 2-3 2 3" />
        </>,
      )}
    </span>
  );
}

export function ElevenLabsLogo({ size = 22, title = 'ElevenLabs' }: LogoProps) {
  return (
    <span style={{ color: '#FFFFFF' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <rect x="3" y="9" width="2" height="6" />
          <rect x="7" y="6" width="2" height="12" />
          <rect x="11" y="3" width="2" height="18" />
          <rect x="15" y="6" width="2" height="12" />
          <rect x="19" y="9" width="2" height="6" />
        </>,
      )}
    </span>
  );
}

export function WhisperLogo({ size = 22, title = 'Whisper' }: LogoProps) {
  return (
    <span style={{ color: '#10A37F' }} className="integrations-page__logo">
      {wrap(
        size,
        title,
        <>
          <path d="M12 4a3 3 0 0 1 3 3v6a3 3 0 1 1-6 0V7a3 3 0 0 1 3-3z" />
          <path d="M5 12a7 7 0 0 0 14 0" />
          <path d="M12 19v3" />
        </>,
      )}
    </span>
  );
}
