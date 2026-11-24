import type { RunStepResponse } from '@flow-cortex/api-client';
import { Clock } from '@phosphor-icons/react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { useElapsedClock } from './useElapsedClock';

interface StepActivityCardProps {
  step: RunStepResponse;
  stepTitle: string;
  productName?: string | undefined;
}

function runningSentence(
  stepType: string | undefined,
  productName: string | undefined,
  t: TFunction,
): string {
  switch (stepType) {
    case 'generate_script':
      return productName
        ? t('workflow.step.activity.running.generate_script_named', { name: productName })
        : t('workflow.step.activity.running.generate_script');
    case 'generate_storyboard':
      return t('workflow.step.activity.running.generate_storyboard');
    case 'generate_speech':
      return t('workflow.step.activity.running.generate_speech');
    case 'generate_video':
      return t('workflow.step.activity.running.generate_video');
    case 'render_video':
      return t('workflow.step.activity.running.render_video');
    default:
      return t('workflow.step.activity.running.fallback');
  }
}

/** Minimalist activity panel shown while a Product Video step is actively running or queued. */
export function StepActivityCard({
  step,
  stepTitle,
  productName,
}: Readonly<StepActivityCardProps>) {
  const { t } = useTranslation();
  const isRunning = step.status === 'running';
  const elapsed = useElapsedClock(step.pickedUpAt ?? step.startedAt, step.finishedAt, isRunning);

  if (isRunning) {
    const waitingPickup = !step.pickedUpAt;
    const hasPercent = typeof step.progressPercent === 'number' && !waitingPickup;
    const heading = step.stepOrder !== undefined ? `${step.stepOrder}. ${stepTitle}` : stepTitle;

    return (
      <div className="pv-activity-card">
        <div className="pv-activity-head">
          <h3 className="pv-activity-title">{heading}</h3>
          {elapsed && (
            <span className="pv-activity-elapsed">
              <Clock size={14} aria-hidden="true" />
              {t('workflow.step.activity.elapsed', { time: elapsed })}
            </span>
          )}
        </div>

        {hasPercent ? (
          <div className="run-step-progress">
            <progress
              max={100}
              value={step.progressPercent}
              aria-label={t('workflow.step.progress', { order: step.stepOrder })}
            />
            <span>{`${step.progressPercent}%`}</span>
          </div>
        ) : (
          <div
            className="pv-indeterminate-track"
            role="progressbar"
            aria-label={t('workflow.step.progress', { order: step.stepOrder })}
          >
            <div className="pv-indeterminate-bar" />
          </div>
        )}

        <p className="pv-activity-line">
          {waitingPickup
            ? t('workflow.step.activity.waitingPickup')
            : runningSentence(step.type, productName, t)}
        </p>
      </div>
    );
  }

  if (step.status === 'pending' || !step.status) {
    const heading = step.stepOrder !== undefined ? `${step.stepOrder}. ${stepTitle}` : stepTitle;
    const pendingKey =
      step.type === 'APPROVAL_CHECKPOINT'
        ? 'workflow.step.activity.pending.APPROVAL_CHECKPOINT'
        : 'workflow.step.activity.pending.default';

    return (
      <div className="pv-activity-card pv-activity-card--pending">
        <div className="pv-activity-head">
          <h3 className="pv-activity-title">{heading}</h3>
          <span className="run-step-status" data-state="pending">
            {t('workflow.step.state.pending')}
          </span>
        </div>
        <p className="pv-activity-line">{t(pendingKey)}</p>
      </div>
    );
  }

  return null;
}
