import { useQueryClient } from '@tanstack/react-query';
import type { TFunction } from 'i18next';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { StartRunRequest } from '@flow-cortex/api-client';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { activeLocale } from '../../lib/i18n';
import { CreditsRefusal } from '../credits/CreditsRefusal';
import { invalidateBalance } from '../credits/useBalance';
import { startProblem, type IncompleteWorkflow } from './startProblem';
import { mediaFileProblem, NO_FILE, type FileProblem } from './startRunSchema';
import { createStartWithUpload, StartStageError, type StartPhase } from './startWithUpload';
import { uploadFile } from './uploadFile';
import { useCreateUploadMutation, useStartRunMutation } from './useWorkflows';

type Failure =
  | { kind: 'upload' }
  | { kind: 'alert'; message: string }
  | { kind: 'credits'; message: string; resend: StartRunRequest | null };

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
  const { t } = useTranslation();
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
    const stageError = error instanceof StartStageError ? error : null;
    const problem = startProblem(stageError ? stageError.cause : error, undefined, t, activeLocale());
    if (problem.kind === 'field') {
      setFieldProblem({ kind: 'server', message: problem.message });
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
      setFieldProblem(asCheck(NO_FILE));
      return;
    }
    if (mediaFileProblem(file)) {
      return;
    }
    void run(file);
  };

  const resend = failure?.kind === 'credits' ? failure.resend : null;

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
            <p>
              {fieldProblem.kind === 'server'
                ? fieldProblem.message
                : t(fieldProblem.problem.messageKey, fieldProblem.problem.values ?? {})}
            </p>
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
          <p>{failure.message}</p>
          <p>{t('workflow.start.nextActionLater')}</p>
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
        {startButtonLabel(phase, t)}
      </button>
    </form>
  );
}
