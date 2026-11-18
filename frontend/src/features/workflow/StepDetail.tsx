import type { RunStepResponse } from '@flow-cortex/api-client';
import type { RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { ApprovalPanel } from './ApprovalPanel';
import { parseStoryboardOutput } from './productVideoSchemas';
import { StepActivityCard } from './StepActivityCard';
import { StepDownloads } from './StepDownloads';
import { formatTime, stepDuration, stepKey, stepNameId } from './stepDisplay';
import { stepErrorSentence } from './stepErrorMessages';
import { StepOutput } from './StepOutput';
import { StoredOutputDownload } from './StoredOutputDownload';
import { StoryboardOutputView } from './StoryboardOutputView';

function StepProgress({ step }: Readonly<{ step: RunStepResponse }>) {
  const { t } = useTranslation();
  if (step.status !== 'running') {
    return null;
  }
  if (!step.pickedUpAt) {
    return <p className="run-step-waiting">{t('workflow.step.waitingToStart')}</p>;
  }
  if (step.progressPercent === undefined || step.progressPercent === null) {
    return null;
  }
  return (
    <p className="run-step-progress">
      <progress
        max={100}
        value={step.progressPercent}
        aria-label={t('workflow.step.progress', { order: step.stepOrder })}
      />
      <span>{step.progressPercent} %</span>
    </p>
  );
}

interface StepDetailProps {
  id: string;
  panelRef: RefObject<HTMLElement>;
  runId: string;
  step: RunStepResponse;
  /** The run is paused for the member's approval, on this step or another. */
  awaitingApproval: boolean;
  /** The run is paused on this step: the panel shows the approval form. */
  needsYou: boolean;
  fileName: string | undefined;
  isProductVideo?: boolean | undefined;
  stepTitle?: string | undefined;
  productName?: string | undefined;
}

function visibleProductVideoArtifacts(
  stepType: string | undefined,
  artifacts: readonly NonNullable<RunStepResponse['outputs']>[number][],
) {
  if (stepType === 'render_video') {
    return artifacts.filter((a) => a.mime?.startsWith('video/') || a.name.endsWith('.mp4'));
  }
  if (stepType === 'generate_speech') {
    return artifacts.filter(
      (a) => a.mime?.startsWith('audio/') || a.name.endsWith('.wav') || a.name.endsWith('.mp3'),
    );
  }
  if (stepType === 'generate_video') {
    return artifacts.filter((a) => a.mime?.startsWith('video/') || a.name.endsWith('.mp4'));
  }
  return [];
}

/** The details of the selected step: progress, output, downloads, or the failure and its code. */
export function StepDetail({
  id,
  panelRef,
  runId,
  step,
  awaitingApproval,
  needsYou,
  fileName,
  isProductVideo = false,
  stepTitle,
  productName,
}: Readonly<StepDetailProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const status = step.status ?? 'pending';
  const outputs = step.outputs ?? [];
  const details = outputs.find((output) => output.mime === 'application/json');
  const artifacts = outputs.filter((output) => output.mime !== 'application/json');
  const displayedArtifacts = isProductVideo
    ? visibleProductVideoArtifacts(step.type, artifacts)
    : artifacts;
  const storyboard =
    step.type === 'generate_storyboard' ? parseStoryboardOutput(step.output) : null;
  const allowDownloadForStep = !isProductVideo || step.type === 'render_video';
  const stepOrder = step.stepOrder;
  const duration = stepDuration(step, t, locale);
  const started = formatTime(step.startedAt, locale);
  const finished = formatTime(step.finishedAt, locale);

  return (
    <section
      id={id}
      ref={panelRef}
      className="step-detail"
      aria-labelledby={stepNameId(stepKey(step))}
    >
      {(duration || started || finished) && (
        <div className="step-timing-bar">
          {duration && (
            <span className="step-timing-pill" title={t('workflow.step.timing.timeTaken')}>
              <span className="timing-label">{t('workflow.step.timing.duration')}</span>
              <span className="timing-val">{duration}</span>
            </span>
          )}
          {started && (
            <span className="step-timing-pill" title={t('workflow.step.timing.startTime')}>
              <span className="timing-label">{t('workflow.step.timing.started')}</span>
              <span className="timing-val">{started}</span>
            </span>
          )}
          {finished && (
            <span className="step-timing-pill" title={t('workflow.step.timing.finishTime')}>
              <span className="timing-label">{t('workflow.step.timing.finished')}</span>
              <span className="timing-val">{finished}</span>
            </span>
          )}
        </div>
      )}

      {awaitingApproval && (
        // Stays mounted while another step is shown, so an unsaved edit is still there on return.
        <div className="step-detail-body" hidden={!needsYou}>
          <ApprovalPanel runId={runId} />
        </div>
      )}
      <div key={stepKey(step)} className="step-detail-body">
        {isProductVideo && !needsYou && (status === 'running' || status === 'pending') ? (
          <StepActivityCard
            step={step}
            stepTitle={stepTitle ?? t('workflow.step.fallback', { order: step.stepOrder })}
            productName={productName}
          />
        ) : (
          <StepProgress step={step} />
        )}
        {status === 'completed' && storyboard ? (
          <StoryboardOutputView storyboard={storyboard} />
        ) : (
          status === 'completed' &&
          step.output && <StepOutput output={step.output} hideRaw={isProductVideo} />
        )}
        {status === 'completed' && !isProductVideo && details && stepOrder !== undefined && (
          <StepDownloads
            runId={runId}
            stepOrder={stepOrder}
            output={step.output}
            details={details}
            fileName={fileName}
          />
        )}
        {status === 'completed' &&
          stepOrder !== undefined &&
          displayedArtifacts.map((artifact) => (
            <StoredOutputDownload
              key={artifact.name}
              runId={runId}
              stepOrder={stepOrder}
              output={artifact}
              allowDownload={allowDownloadForStep}
              downloadLabel={
                isProductVideo && step.type === 'render_video'
                  ? t('workflow.download.downloadFinalStepVideo', { name: artifact.name })
                  : undefined
              }
            />
          ))}
        {status === 'error' && (
          <div className="run-step-failure">
            <p role="alert" className="run-step-error">
              {stepErrorSentence(
                step.errorCode,
                step.errorMessage,
                step.mode === 'remote',
                t,
                locale,
              )}
            </p>
            {step.errorCode && <small className="run-step-code">{step.errorCode}</small>}
          </div>
        )}
      </div>
    </section>
  );
}
