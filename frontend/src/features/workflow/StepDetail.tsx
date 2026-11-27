import type { RunStepResponse } from '@flow-cortex/api-client';
import { useEffect, useReducer, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { ApprovalPanel } from './ApprovalPanel';
import { parseStoryboardOutput } from './productVideoSchemas';
import { runningAdvice } from './runResultModel';
import { StepActivityCard } from './StepActivityCard';
import { StepDownloads } from './StepDownloads';
import {
  formatTime,
  stateWord,
  stepDuration,
  stepKey,
  stepNameId,
  type StepState,
} from './stepDisplay';
import { stepErrorSentence } from './stepErrorMessages';
import { StatusGlyph, TypeGlyph } from './stepGlyphs';
import { StepOutcome } from './StepOutcome';
import { StepOutput } from './StepOutput';
import './StepResult.css';
import { StoredOutputDownload } from './StoredOutputDownload';
import { StoryboardOutputView } from './StoryboardOutputView';

interface ResultHeaderProps {
  step: RunStepResponse;
  position: number;
  name: string;
  state: StepState;
}

/** "Result", the step's glyph, "N. name" and the status word the node shows. */
function ResultHeader({ step, position, name, state }: Readonly<ResultHeaderProps>) {
  const { t } = useTranslation();
  return (
    <div className="step-result-head">
      <span className="step-result-kicker">{t('workflow.result.label')}</span>
      <span className="step-result-title">
        <TypeGlyph type={step.type} />
        <span>{`${position}. ${name}`}</span>
      </span>
      <span className="run-step-status" data-state={state}>
        <StatusGlyph state={state} />
        {stateWord(state, t)}
      </span>
    </div>
  );
}

interface StepWorkingProps {
  step: RunStepResponse;
  state: StepState;
  runStatus: string | undefined;
  runStartedAt: string | undefined;
}

/**
 * A running step: waiting to start, or skeleton lines, the working line and its notes in one polite
 * region, and the progress bar outside it. One timer wakes the panel at the next note's threshold.
 */
function StepWorking({ step, state, runStatus, runStartedAt }: Readonly<StepWorkingProps>) {
  const { t } = useTranslation();
  const [wakeups, wake] = useReducer((count: number) => count + 1, 0);
  const advice = runningAdvice(
    { pickedUpAt: step.pickedUpAt, runStartedAt, runStatus, stepState: state },
    Date.now(),
  );
  const { nextChangeInMs } = advice;
  useEffect(() => {
    if (nextChangeInMs === null) {
      return;
    }
    const timer = setTimeout(wake, nextChangeInMs);
    return () => clearTimeout(timer);
  }, [nextChangeInMs, wakeups]);

  if (!step.pickedUpAt) {
    return <p className="run-step-waiting">{t('workflow.step.waitingToStart')}</p>;
  }
  const percent = step.progressPercent;
  return (
    <div className="step-result-working">
      <div className="step-result-skeleton" aria-hidden="true">
        <span className="step-result-skeleton-line" />
        <span className="step-result-skeleton-line" />
        <span className="step-result-skeleton-line" />
      </div>
      <div role="status">
        <p className="step-result-working-line">{t('workflow.result.working')}</p>
        {advice.saved && <p className="step-result-note">{t('workflow.result.savedNote')}</p>}
        {advice.longRun === 1 && (
          <p className="step-result-note">{t('workflow.result.longRunNote')}</p>
        )}
        {advice.longRun === 2 && (
          <p className="step-result-note" data-tone="amber">
            {t('workflow.result.veryLongRunNote')}
          </p>
        )}
      </div>
      {percent !== undefined && percent !== null && (
        <p className="run-step-progress">
          <progress
            max={100}
            value={percent}
            aria-label={t('workflow.step.progress', { order: step.stepOrder })}
          />
          <span>{percent} %</span>
        </p>
      )}
    </div>
  );
}

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
  /** The state the node shows for this step. */
  state: StepState;
  /** The node's 1-based position and its label, for the result header. */
  position: number;
  name: string;
  runStatus: string | undefined;
  runStartedAt: string | undefined;
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
  state,
  position,
  name,
  runStatus,
  runStartedAt,
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
    isProductVideo && step.type === 'generate_storyboard'
      ? parseStoryboardOutput(step.output)
      : null;
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
      {!isProductVideo && <ResultHeader step={step} position={position} name={name} state={state} />}
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
        ) : isProductVideo ? (
          <StepProgress step={step} />
        ) : (
          status === 'running' && (
            <StepWorking
              step={step}
              state={state}
              runStatus={runStatus}
              runStartedAt={runStartedAt}
            />
          )
        )}
        {!isProductVideo && status === 'pending' && !needsYou && (
          <p className="step-result-pending">{t('workflow.result.noResultYet')}</p>
        )}
        {!isProductVideo && status === 'skipped' && (
          <p className="step-result-pending">{t('workflow.result.skipped')}</p>
        )}
        {status === 'completed' && !isProductVideo && <StepOutcome step={step} text={step.output} />}
        {status === 'completed' && isProductVideo && storyboard ? (
          <StoryboardOutputView storyboard={storyboard} />
        ) : (
          status === 'completed' &&
          isProductVideo &&
          step.output && <StepOutput output={step.output} hideRaw />
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
                step.type,
              )}
            </p>
          </div>
        )}
      </div>
    </section>
  );
}
