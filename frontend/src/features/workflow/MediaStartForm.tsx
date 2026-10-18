import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { mediaFileProblem, NO_FILE, type FileProblem } from './startRunSchema';
import { createStartWithUpload, StartStageError, type StartPhase } from './startWithUpload';
import { uploadFile } from './uploadFile';
import { useCreateUploadMutation, useStartRunMutation } from './useWorkflows';

/** `detail` is the server's own sentence, shown as received; without one the form's fallback is shown. */
type Failure = { kind: 'upload' } | { kind: 'alert'; detail: string | undefined };

/** A check made here (translated at render) or a field message the server wrote (shown as received). */
type FieldProblem = { kind: 'check'; problem: FileProblem } | { kind: 'server'; message: string };

const asCheck = (problem: FileProblem | null): FieldProblem | null =>
  problem ? { kind: 'check', problem } : null;

function startButtonLabel(phase: StartPhase | null, t: TFunction): string {
  if (phase?.kind === 'uploading') {
    return t('workflow.start.uploadingPercent', { percent: phase.percent });
  }
  return phase?.kind === 'starting' ? t('workflow.start.starting') : t('workflow.start.button');
}

/**
 * Start form of a workflow whose first step works on an uploaded file. Pressing Start uploads the
 * picked file and then starts the run, as one sequence; Start stays focusable while it runs and a
 * press during it changes nothing.
 */
export function MediaStartForm({
  workflowId,
  disabled,
}: Readonly<{ workflowId: string; disabled: boolean }>) {
  const { t } = useTranslation();
  const { go } = useTransitionNavigate();
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
  const [fieldProblem, setFieldProblem] = useState<FieldProblem | null>(null);
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
    setFieldProblem(picked ? asCheck(mediaFileProblem(picked)) : null);
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
      setFieldProblem({ kind: 'server', message: fieldMessage });
      return;
    }
    setFailure({ kind: 'alert', detail: problem?.detail });
  };

  const run = async (picked: File) => {
    setFailure(null);
    setFieldProblem(null);
    try {
      const runId = await start(picked, setPhase);
      // `null`: this press came while a start was already running, which owns the form state.
      if (runId !== null && mounted.current) {
        go(`/workflows/${workflowId}/runs/${runId}`);
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
      setFieldProblem(asCheck(NO_FILE));
      return;
    }
    if (mediaFileProblem(file)) {
      return;
    }
    void run(file);
  };

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-label={t('workflow.start.formLabel')}
      className="media-start-form"
    >
      <div className="field">
        <label htmlFor="run-file">{t('workflow.start.mediaFile')}</label>
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
            {fieldProblem.kind === 'server' ? (
              <p>{fieldProblem.message}</p>
            ) : (
              <>
                <p>{t(fieldProblem.problem.messageKey)}</p>
                {fieldProblem.problem.nextActionKey && (
                  <p>{t(fieldProblem.problem.nextActionKey)}</p>
                )}
              </>
            )}
          </div>
        )}
      </div>
      {phase && (
        <progress
          className="upload-progress"
          max={100}
          value={phase.kind === 'uploading' ? phase.percent : 100}
          aria-label={t('workflow.start.uploadProgress')}
        />
      )}
      {failure?.kind === 'upload' && (
        <div role="alert">
          <p>{t('workflow.start.uploadUnfinished')}</p>
          <button type="button" onClick={() => file && void run(file)}>
            {t('workflow.start.retry')}
          </button>
        </div>
      )}
      {failure?.kind === 'alert' && (
        <div role="alert">
          <p>{failure.detail ?? t('workflow.start.failed')}</p>
          <p>{t('workflow.start.nextActionLater')}</p>
        </div>
      )}
      <button type="submit" aria-busy={phase !== null} disabled={disabled}>
        {startButtonLabel(phase, t)}
      </button>
    </form>
  );
}
