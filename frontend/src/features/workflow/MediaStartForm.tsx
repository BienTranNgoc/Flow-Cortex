import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import type { StartRunRequest } from '@flow-cortex/api-client';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { CreditsRefusal } from '../credits/CreditsRefusal';
import { invalidateBalance } from '../credits/useBalance';
import { startProblem, type IncompleteWorkflow } from './startProblem';
import { mediaFileProblem, NO_FILE, type FileProblem } from './startRunSchema';
import { createStartWithUpload, StartStageError, type StartPhase } from './startWithUpload';
import { uploadFile } from './uploadFile';
import { useCreateUploadMutation, useStartRunMutation } from './useWorkflows';

const NEXT_ACTION = 'Try again later, or go back to the Workflows list.';

type Failure =
  | { kind: 'upload' }
  | { kind: 'alert'; message: string }
  | { kind: 'credits'; message: string; resend: StartRunRequest | null };

function startButtonLabel(phase: StartPhase | null): string {
  if (phase?.kind === 'uploading') {
    return `Uploading… ${phase.percent} %`;
  }
  return phase?.kind === 'starting' ? 'Starting…' : 'Start run';
}

/**
 * Start form of a workflow whose first step works on an uploaded file. Pressing Start uploads the
 * picked file and then starts the run, as one sequence; Start stays focusable while it runs and a
 * press during it changes nothing. A refusal because a step is not configured goes to
 * `onIncomplete`, which the page shows next to the pipeline; every new start clears it.
 */
export function MediaStartForm({
  workflowId,
  disabled,
  onIncomplete,
}: Readonly<{
  workflowId: string;
  disabled: boolean;
  onIncomplete: (problem: IncompleteWorkflow | null) => void;
}>) {
  const { go } = useTransitionNavigate();
  const queryClient = useQueryClient();
  const createUpload = useCreateUploadMutation();
  const startRun = useStartRunMutation(workflowId);
  const start = useMemo(
    () =>
      createStartWithUpload({
        requestUpload: createUpload.mutateAsync,
        uploadFile,
        startRun: startRun.mutateAsync,
      }),
    [createUpload.mutateAsync, startRun.mutateAsync],
  );
  const [file, setFile] = useState<File | null>(null);
  const [fieldProblem, setFieldProblem] = useState<FileProblem | null>(null);
  const [phase, setPhase] = useState<StartPhase | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);

  // A run started from here still starts after the member left, but must not pull them back to it.
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    if (phase) {
      return;
    }
    const picked = event.target.files?.[0] ?? null;
    setFile(picked);
    setFailure(null);
    setFieldProblem(picked ? mediaFileProblem(picked) : null);
  };

  const showFailure = (error: unknown) => {
    if (error instanceof StartStageError && error.stage === 'upload') {
      setFailure({ kind: 'upload' });
      return;
    }
    const stageError = error instanceof StartStageError ? error : null;
    const problem = startProblem(stageError ? stageError.cause : error);
    if (problem.kind === 'field') {
      setFieldProblem({ message: problem.message });
    } else if (problem.kind === 'incomplete') {
      onIncomplete(problem);
    } else if (problem.kind === 'credits') {
      const refused = problem.canRunAnyway ? stageError?.request : undefined;
      setFailure({
        kind: 'credits',
        message: problem.message,
        resend: refused ? { ...refused, acknowledgeCost: true } : null,
      });
    } else {
      setFailure({ kind: 'alert', message: problem.message });
    }
  };

  const settle = async (started: Promise<string | null>) => {
    try {
      const runId = await started;
      // `null`: this press came while a start was already running, which owns the form state.
      if (runId !== null) {
        void invalidateBalance(queryClient);
        if (mounted.current) {
          go(`/workflows/${workflowId}/runs/${runId}`);
        }
      }
    } catch (error) {
      setPhase(null);
      showFailure(error);
    }
  };

  const clearProblems = () => {
    setFailure(null);
    setFieldProblem(null);
    onIncomplete(null);
  };

  const run = (picked: File) => {
    clearProblems();
    return settle(start(picked, setPhase));
  };

  const runAnyway = (request: StartRunRequest) => {
    clearProblems();
    return settle(start.resend(request, setPhase));
  };

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (phase) {
      return;
    }
    if (!file) {
      setFieldProblem(NO_FILE);
      return;
    }
    if (mediaFileProblem(file)) {
      return;
    }
    void run(file);
  };

  const resend = failure?.kind === 'credits' ? failure.resend : null;

  return (
    <form onSubmit={onSubmit} noValidate aria-label="Start run" className="media-start-form">
      <div className="field">
        <label htmlFor="run-file">Media file</label>
        <input
          id="run-file"
          type="file"
          required
          onChange={onPick}
          aria-invalid={fieldProblem ? true : undefined}
          aria-describedby={fieldProblem ? 'run-file-problem' : undefined}
        />
        {fieldProblem && (
          <div id="run-file-problem" className="field-error" role="alert">
            <p>{fieldProblem.message}</p>
          </div>
        )}
      </div>
      {phase && (
        <progress
          className="upload-progress"
          max={100}
          value={phase.kind === 'uploading' ? phase.percent : 100}
          aria-label="Upload progress"
        />
      )}
      {failure?.kind === 'upload' && (
        <div role="alert">
          <p>The upload didn&apos;t finish. Try again.</p>
          <button type="button" onClick={() => file && void run(file)}>
            Retry
          </button>
        </div>
      )}
      {failure?.kind === 'alert' && (
        <div role="alert">
          <p>{failure.message}</p>
          <p>{NEXT_ACTION}</p>
        </div>
      )}
      {failure?.kind === 'credits' && (
        <CreditsRefusal
          message={failure.message}
          busy={phase !== null}
          onRunAnyway={resend ? () => void runAnyway(resend) : undefined}
        />
      )}
      <button type="submit" aria-busy={phase !== null} disabled={disabled}>
        {startButtonLabel(phase)}
      </button>
    </form>
  );
}
