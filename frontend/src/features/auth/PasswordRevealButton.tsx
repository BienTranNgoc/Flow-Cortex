import { useTranslation } from 'react-i18next';

const eyeIcon = (
  <svg
    viewBox="0 0 24 24"
    width="20"
    height="20"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const eyeOffIcon = (
  <svg
    viewBox="0 0 24 24"
    width="20"
    height="20"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.2M6.6 6.6C3.8 8.4 2 12 2 12s3.6 7 10 7a10.4 10.4 0 0 0 5.4-1.6" />
    <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M3 3l18 18" />
  </svg>
);

interface PasswordRevealButtonProps {
  shown: boolean;
  controlsId: string;
  onToggle: () => void;
}

export function PasswordRevealButton({ shown, controlsId, onToggle }: Readonly<PasswordRevealButtonProps>) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="auth-reveal"
      aria-label={t('auth.login.showPassword')}
      aria-pressed={shown}
      aria-controls={controlsId}
      onClick={onToggle}
    >
      {shown ? eyeOffIcon : eyeIcon}
    </button>
  );
}
