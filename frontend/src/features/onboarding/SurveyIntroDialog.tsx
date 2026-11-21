import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { JourneyDialog } from './JourneyDialog';

interface SurveyIntroDialogProps {
  onStart: () => void;
  onLater: () => void;
  onBack: () => void;
  onClose: () => void;
}

export function SurveyIntroDialog({
  onStart,
  onLater,
  onBack,
  onClose,
}: Readonly<SurveyIntroDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <JourneyDialog
      titleId={titleId}
      title={t('onboarding.survey.title')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onBack}>
            {t('onboarding.survey.back')}
          </button>
          <button type="button" onClick={onLater}>
            {t('onboarding.survey.later')}
          </button>
          <button type="button" className="onboarding-primary" onClick={onStart}>
            {t('onboarding.survey.start')}
          </button>
        </>
      }
    >
      <p>{t('onboarding.survey.text')}</p>
    </JourneyDialog>
  );
}
