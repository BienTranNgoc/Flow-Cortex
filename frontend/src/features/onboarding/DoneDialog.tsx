import type { OnboardingSampleWorkflow } from '@flow-cortex/api-client';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { JourneyStep } from './onboardingFlow';
import { JourneyDialog } from './JourneyDialog';
import { SampleCard } from './SampleCard';

interface DoneDialogProps {
  steps: readonly JourneyStep[];
  sample: OnboardingSampleWorkflow | null;
  /** The sample button was pressed and the journey is finishing. */
  trying: boolean;
  onFinish: () => void;
  onTrySample: (sample: OnboardingSampleWorkflow) => void;
  onBack: () => void;
  onClose: () => void;
}

export function DoneDialog({
  steps,
  sample,
  trying,
  onFinish,
  onTrySample,
  onBack,
  onClose,
}: Readonly<DoneDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <JourneyDialog
      titleId={titleId}
      title={t('onboarding.done.title')}
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            aria-disabled={trying}
            onClick={() => {
              if (!trying) onBack();
            }}
          >
            {t('onboarding.quiz.back')}
          </button>
          <button
            type="button"
            className="onboarding-primary"
            aria-disabled={trying}
            onClick={() => {
              if (!trying) onFinish();
            }}
          >
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
      {sample && <SampleCard sample={sample} busy={trying} onTry={onTrySample} />}
    </JourneyDialog>
  );
}
