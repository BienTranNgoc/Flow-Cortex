import { ApiError } from '@flow-cortex/api-client';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { mediaFileProblem, NO_FILE, type FileProblem } from './startRunSchema';
import { createStartWithUpload, StartStageError, type StartPhase } from './startWithUpload';
import { uploadFile } from './uploadFile';
import { useCreateUploadMutation, useStartRunMutation } from './useWorkflows';

const NEXT_ACTION = 'Try again later, or go back to the Workflows list.';

type Failure = { kind: 'upload' } | { kind: 'alert'; message: string };

function startButtonLabel(phase: StartPhase | null): string {
  if (phase?.kind === 'uploading') {
    return `Uploading… ${phase.percent} %`;
  }
  return phase?.kind === 'starting' ? 'Starting…' : 'Start run';
}

/**
 * Start form of a workflow whose first step works on an uploaded file. Pressing Start uploads the
 * picked file and then starts the run, as one sequence; Start stays focusable while it runs and a
 * press during it changes nothing.
 */
export function MediaStartForm({ workflowId }: { workflowId: string }) {
  const navigate = useNavigate();
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
    const cause = error instanceof StartStageError ? error.cause : error;
    const problem = cause instanceof ApiError ? cause.problem : undefined;
    const fieldMessage =
      problem?.errors?.uploadId ?? problem?.errors?.size ?? problem?.errors?.fileName;
    if (fieldMessage) {
      setFieldProblem({ message: fieldMessage });
      return;
    }
    setFailure({ kind: 'alert', message: problem?.detail ?? 'Could not start the run.' });
  };

  const run = async (picked: File) => {
    setFailure(null);
    setFieldProblem(null);
    try {
      const runId = await start(picked, setPhase);
      // `null`: this press came while a start was already running, which owns the form state.
      if (runId !== null && mounted.current) {
        navigate(`/workflows/${workflowId}/runs/${runId}`);
      }
    } catch (error) {
      setPhase(null);
      showFailure(error);
    }
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
            {fieldProblem.nextAction && <p>{fieldProblem.nextAction}</p>}
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
      <button type="submit" aria-busy={phase !== null}>
        {startButtonLabel(phase)}
      </button>
    </form>
  );
}
