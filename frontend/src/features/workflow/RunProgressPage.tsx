import { ApiError, type RunResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useFeatures } from '../../app/useFeatures';
import './WorkflowPages.css';
import { Pipeline } from './Pipeline';
import { StepDetail } from './StepDetail';
import { StopRunDialog } from './StopRunDialog';
import {
  choiceTakenBack,
  needsYouStep,
  selectedStep,
  stateWord,
  stepKey,
  stepLabel,
  stepState,
  type StepChoice,
} from './stepDisplay';
import { NO_MESSAGE } from './stepErrorMessages';
import { useCancelRunMutation, useRunQuery } from './useWorkflows';

const RUN_HEADING_KEYS: Record<string, string> = {
  pending: 'workflow.run.heading.pending',
  running: 'workflow.run.heading.running',
  waiting_approval: 'workflow.run.heading.waitingApproval',
  completed: 'workflow.run.heading.completed',
  error: 'workflow.run.heading.error',
  cancelling: 'workflow.run.heading.cancelling',
  cancelled: 'workflow.run.heading.cancelled',
};

function runHeading(status: string | undefined, t: TFunction): string {
  const key = RUN_HEADING_KEYS[status ?? ''];
  return key ? t(key) : t('workflow.run.headingOther', { status });
}

const STOPPABLE: ReadonlySet<string> = new Set(['running', 'waiting_approval']);

/** A refusal keeps the server's own sentence; a broken request or a server fault gets ours. */
function stopProblem(error: unknown, t: TFunction): string {
  const refusal =
    error instanceof ApiError && error.status < 500 ? error.problem?.detail : undefined;
  return refusal ?? t('workflow.stop.failed');
}

interface StopControlProps {
  runId: string;
  chargedCredits: number | null | undefined;
  onStopped: () => void;
}

/** "Stop workflow", its confirmation, and what a failed stop says. Mounted only on a stoppable run. */
function StopControl({ runId, chargedCredits, onStopped }: Readonly<StopControlProps>) {
  const { t } = useTranslation();
  const { runCancellation } = useFeatures();
  const cancel = useCancelRunMutation(runId);
  const [open, setOpen] = useState(false);
  const [creditsAtOpen, setCreditsAtOpen] = useState<number | null | undefined>();
  // `isPending` lands a render later than a second press of a double click.
  const sending = useRef(false);

  if (!runCancellation) {
    return null;
  }

  const onOpen = () => {
    cancel.reset();
    setCreditsAtOpen(chargedCredits);
    setOpen(true);
  };
  const onStop = async () => {
    if (sending.current) return;
    sending.current = true;
    try {
      await cancel.mutateAsync();
      setOpen(false);
      onStopped();
    } catch {
      // The mutation holds the error, which the alert below reads.
      setOpen(false);
    } finally {
      sending.current = false;
    }
  };

  return (
    <div className="run-stop">
      <button type="button" className="run-stop-button" aria-haspopup="dialog" onClick={onOpen}>
        {t('workflow.stop.open')}
      </button>
      {cancel.isError && (
        <p role="alert" className="run-stop-error">
          {stopProblem(cancel.error, t)}
        </p>
      )}
      <StopRunDialog
        open={open}
        chargedCredits={creditsAtOpen}
        stopping={cancel.isPending}
        onKeep={() => setOpen(false)}
        onStop={() => void onStop()}
      />
    </div>
  );
}

/**
 * Runs written before uploads existed may hold a JSON reference as their input; that is never a
 * file name, so it is not shown.
 */
function looksLikeJson(value: string): boolean {
  try {
    const parsed: unknown = JSON.parse(value);
    return typeof parsed === 'object' && parsed !== null;
  } catch {
    return false;
  }
}

/** The state `useTransitionNavigate` attaches when a member has just started this run. */
function isFocusRequest(state: unknown): boolean {
  return (
    typeof state === 'object' && state !== null && 'focusHeading' in state && !!state.focusHeading
  );
}

interface RunViewProps {
  run: RunResponse;
  runId: string;
  workflowId: string | undefined;
}

function RunView({ run, runId, workflowId }: Readonly<RunViewProps>) {
  const { t } = useTranslation();
  const panelId = useId();
  const panelRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const [choice, setChoice] = useState<StepChoice>();
  const steps = run.steps ?? [];
  if (choice && choiceTakenBack(steps, run.status, choice)) {
    setChoice(undefined);
  }
  const selected = selectedStep(steps, run.status, choice);

  const asksForFocus = isFocusRequest(location.state);
  useEffect(() => {
    if (asksForFocus) {
      headingRef.current?.focus();
      // The request lives in the history entry, so a reload would repeat it.
      navigate(location.pathname + location.search + location.hash, {
        replace: true,
        state: null,
      });
    }
  }, [asksForFocus, location.pathname, location.search, location.hash, navigate]);
  // "Stop workflow" leaves with the status it was offered under, so focus moves to the heading.
  const [stopped, setStopped] = useState(false);
  useEffect(() => {
    if (stopped) headingRef.current?.focus();
  }, [stopped]);

  const selectedState = selected ? stepState(selected, steps, run.status) : undefined;
  const announcement =
    selected && selectedState ? `${stepLabel(selected, t)}, ${stateWord(selectedState, t)}` : '';

  const selectedKey = selected && stepKey(selected);
  const [announced, setAnnounced] = useState('');
  const previousKey = useRef(selectedKey);
  useEffect(() => {
    if (previousKey.current !== selectedKey) {
      previousKey.current = selectedKey;
      setAnnounced(announcement);
    }
  }, [selectedKey, announcement]);

  const startsWithUpload = steps[0]?.mode === 'remote';
  const fileName =
    startsWithUpload && run.input && !looksLikeJson(run.input) ? run.input : undefined;
  const failedWithoutStep =
    run.status === 'error' && !steps.some((step) => step.status === 'error');
  const backTo = workflowId ?? run.workflowId;

  const onSelect = (stepId: string) => {
    const waitingOn = needsYouStep(steps, run.status);
    setChoice({
      stepId,
      runStatus: run.status,
      needsYouId: waitingOn && stepKey(waitingOn),
    });
    const reduced =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    panelRef.current?.scrollIntoView({ block: 'nearest', behavior: reduced ? 'auto' : 'smooth' });
  };

  return (
    <main>
      <h1 ref={headingRef} tabIndex={-1}>
        {runHeading(run.status, t)}
      </h1>
      {STOPPABLE.has(run.status ?? '') && (
        <StopControl
          runId={runId}
          chargedCredits={run.chargedCredits}
          onStopped={() => setStopped(true)}
        />
      )}
      <div role="status" className="visually-hidden">
        {announced}
      </div>
      {failedWithoutStep && (
        <p role="alert" className="run-step-error">
          {t(NO_MESSAGE)}
        </p>
      )}
      {fileName && <p className="run-file">{t('workflow.file', { name: fileName })}</p>}
      {selected && (
        <div className="run-layout">
          <div className="run-layout-pipeline">
            <Pipeline
              steps={steps.map((step) => ({
                id: stepKey(step),
                name: stepLabel(step, t),
                run: step,
              }))}
              runStatus={run.status}
              selectedId={stepKey(selected)}
              onSelect={onSelect}
              panelId={panelId}
            />
          </div>
          <div className="run-layout-detail">
            <StepDetail
              id={panelId}
              panelRef={panelRef}
              runId={runId}
              step={selected}
              awaitingApproval={needsYouStep(steps, run.status) !== undefined}
              needsYou={selectedState === 'needs-you'}
              fileName={fileName}
            />
          </div>
        </div>
      )}
      {backTo && (
        <Link className="run-back-link action-link" to={`/workflows/${backTo}`}>
          {t('workflow.run.back')}
        </Link>
      )}
    </main>
  );
}

/**
 * `/workflows/:workflowId/runs/:runId` — polls the run until it reaches a terminal status: each
 * step advances pending -> running -> completed|error. Everything shown comes from the saved run,
 * so a run read long after it finished renders the same as it did then.
 */
export function RunProgressPage() {
  const { workflowId, runId } = useParams<{ workflowId: string; runId: string }>();
  const runQuery = useRunQuery(runId);
  const { t } = useTranslation();

  if (!runId) {
    return <p role="alert">{t('workflow.run.noRun')}</p>;
  }
  if (runQuery.isLoading) {
    return <output>{t('workflow.run.loading')}</output>;
  }
  if (runQuery.isError || !runQuery.data) {
    return <p role="alert">{t('workflow.run.loadError')}</p>;
  }
  return <RunView run={runQuery.data} runId={runId} workflowId={workflowId} />;
}
