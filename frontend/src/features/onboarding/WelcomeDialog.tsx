import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { JourneyDialog } from './JourneyDialog';

interface WelcomeDialogProps {
  onStart: () => void;
  onSkip: () => void;
  onClose: () => void;
}

export function WelcomeDialog({ onStart, onSkip, onClose }: Readonly<WelcomeDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <JourneyDialog
      titleId={titleId}
      title={t('onboarding.welcome.title')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onSkip}>
            {t('onboarding.welcome.skip')}
          </button>
          <button type="button" className="onboarding-primary" onClick={onStart}>
            {t('onboarding.welcome.start')}
          </button>
        </>
      }
    >
      <p>{t('onboarding.welcome.text')}</p>
    </JourneyDialog>
  );
}
