import { ApiError, type RunResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState } from 'react';
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
  STATE_WORDS,
  stepKey,
  stepLabel,
  stepState,
  type StepChoice,
} from './stepDisplay';
import { NO_MESSAGE } from './stepErrorMessages';
import { useCancelRunMutation, useRunQuery } from './useWorkflows';

const RUN_HEADINGS: Record<string, string> = {
  running: 'Run in progress',
  waiting_approval: 'Waiting for your approval',
  cancelling: 'Stopping…',
  cancelled: 'Run cancelled',
};

const STOPPABLE: ReadonlySet<string> = new Set(['running', 'waiting_approval']);

/** A refusal keeps the server's own sentence; a broken request or a server fault gets ours. */
function stopProblem(error: unknown): string {
  const refusal =
    error instanceof ApiError && error.status < 500 ? error.problem?.detail : undefined;
  return refusal ?? "Couldn't stop the run. Try again.";
}

interface StopControlProps {
  runId: string;
  chargedCredits: number | null | undefined;
  onStopped: () => void;
}

/** "Stop workflow", its confirmation, and what a failed stop says. Mounted only on a stoppable run. */
function StopControl({ runId, chargedCredits, onStopped }: Readonly<StopControlProps>) {
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
        Stop workflow
      </button>
      {cancel.isError && (
        <p role="alert" className="run-stop-error">
          {stopProblem(cancel.error)}
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
    selected && selectedState ? `${stepLabel(selected)}, ${STATE_WORDS[selectedState]}` : '';

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
        {RUN_HEADINGS[run.status ?? ''] ?? `Run ${run.status}`}
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
          {NO_MESSAGE}
        </p>
      )}
      {fileName && <p className="run-file">File: {fileName}</p>}
      {selected && (
        <div className="run-layout">
          <div className="run-layout-pipeline">
            <Pipeline
              steps={steps.map((step) => ({ id: stepKey(step), name: stepLabel(step), run: step }))}
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
          Back to workflow
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

  if (!runId) {
    return <p role="alert">No run selected.</p>;
  }
  if (runQuery.isLoading) {
    return <output>Loading run…</output>;
  }
  if (runQuery.isError || !runQuery.data) {
    return <p role="alert">Could not load this run.</p>;
  }
  return <RunView run={runQuery.data} runId={runId} workflowId={workflowId} />;
}
