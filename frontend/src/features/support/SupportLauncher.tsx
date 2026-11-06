import { forwardRef } from 'react';
import { useTranslation } from 'react-i18next';
import './requests.css';

interface SupportLauncherProps {
  expanded: boolean;
  /** An admin reply waits unopened. */
  unread: boolean;
  /** Id of the panel while it is rendered. */
  controls: string | undefined;
  onClick: () => void;
}

function HeadsetMascot() {
  return (
    <svg
      className="support-mascot"
      width="56"
      height="56"
      viewBox="0 0 56 56"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="support-mascot__body"
        d="M12 34C12 21 19 13 28 13S44 21 44 34Q41 37 38.5 34Q36 37 33.3 34Q30.6 37 28 34Q25.4 37 22.7 34Q20 37 17.5 34Q15 37 12 34Z"
        strokeWidth="2.4"
        strokeLinejoin="round"
      />
      <circle className="support-mascot__eye" cx="23" cy="25" r="4.4" strokeWidth="1.8" />
      <circle className="support-mascot__eye" cx="33" cy="25" r="4.4" strokeWidth="1.8" />
      <circle className="support-mascot__pupil" cx="22.4" cy="25.6" r="2" />
      <circle className="support-mascot__pupil" cx="32.4" cy="25.6" r="2" />
      <path
        className="support-mascot__headset"
        d="M10 27C10 15 46 15 46 27"
        strokeWidth="3.4"
        fill="none"
        strokeLinecap="round"
      />
      <rect
        className="support-mascot__earcup"
        x="6.5"
        y="24"
        width="6"
        height="10"
        rx="2.6"
        strokeWidth="1.6"
      />
      <rect
        className="support-mascot__earcup"
        x="43.5"
        y="24"
        width="6"
        height="10"
        rx="2.6"
        strokeWidth="1.6"
      />
    </svg>
  );
}

/** The fixed bottom-right help button; its label shows beside the mascot on wide screens. */
export const SupportLauncher = forwardRef<HTMLButtonElement, SupportLauncherProps>(
  function SupportLauncher({ expanded, unread, controls, onClick }, ref) {
    const { t } = useTranslation();
    return (
      <button
        ref={ref}
        type="button"
        className="support-launcher"
        aria-label={
          unread
            ? `${t('support.launcher.label')}. ${t('support.launcher.unread')}`
            : t('support.launcher.label')
        }
        aria-expanded={expanded}
        aria-controls={controls}
        onClick={onClick}
      >
        <HeadsetMascot />
        {unread && <span className="support-launcher__dot" aria-hidden="true" />}
        <span className="support-launcher__label" aria-hidden="true">
          {t('support.launcher.label')}
        </span>
      </button>
    );
  },
);
