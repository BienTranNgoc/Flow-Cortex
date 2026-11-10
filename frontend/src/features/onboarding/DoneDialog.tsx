import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { JourneyStep } from './onboardingFlow';
import { JourneyDialog } from './JourneyDialog';

interface DoneDialogProps {
  steps: readonly JourneyStep[];
  onFinish: () => void;
  onBack: () => void;
  onClose: () => void;
}

export function DoneDialog({ steps, onFinish, onBack, onClose }: Readonly<DoneDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <JourneyDialog
      titleId={titleId}
      title={t('onboarding.done.title')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onBack}>
            {t('onboarding.quiz.back')}
          </button>
          <button type="button" className="onboarding-primary" onClick={onFinish}>
            {t('onboarding.done.action')}
          </button>
        </>
      }
    >
      <p>{t('onboarding.done.text')}</p>
      {steps.length > 0 && (
        <ul className="onboarding-steps" aria-label={t('onboarding.done.stepsLabel')}>
          {steps.map((step) => (
            <li key={step}>{t(`onboarding.done.steps.${step}`)}</li>
          ))}
        </ul>
      )}
    </JourneyDialog>
  );
}
