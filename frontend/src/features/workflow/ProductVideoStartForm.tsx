import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import type { ProductVideoRunRequest, RunResponse } from '@flow-cortex/api-client';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { CreditsRefusal } from '../credits/CreditsRefusal';
import { invalidateBalance } from '../credits/useBalance';
import {
  createMultiUpload,
  firstImageProblem,
  MultiUploadError,
  type MultiUploadPhase,
} from './productVideoUpload';
import { startProblem, type IncompleteWorkflow } from './startProblem';
import { uploadFile } from './uploadFile';
import {
  useCreateUploadMutation,
  useStartProductVideoRunMutation,
} from './useWorkflows';

const NEXT_ACTION = 'Try again, or go back to the Workflows list.';

type Failure =
  | { kind: 'upload' }
  | { kind: 'alert'; message: string }
  | { kind: 'credits'; message: string; resend: ProductVideoRunRequest | null };

const TEXT_FIELDS = [
  { id: 'product-name', name: 'productName', label: 'Product name', required: true, maxLength: 200 },
  { id: 'product-description', name: 'description', label: 'Description', required: true, maxLength: 4000, multiline: true },
] as const;

type TextFieldName = (typeof TEXT_FIELDS)[number]['name'];

function startButtonLabel(phase: MultiUploadPhase | null): string {
  if (phase?.kind === 'requesting') {
    return 'Preparing…';
  }
  if (phase?.kind === 'uploading') {
    return `Uploading ${phase.index + 1} / ${phase.total}…`;
  }
  return 'Start run';
}

/**
 * Start form for the Product Video workflow. Image picker is the only way to surface a multi-file
 * need; text fields stay plain inputs (1.5.0 rule: every value crossing the trust boundary is
 * validated, the schema is the API's, the form just refuses obvious blanks). A refusal because a
 * step is not configured goes to `onIncomplete`; every new start clears it.
 */
export function ProductVideoStartForm({
  disabled,
  onIncomplete,
}: Readonly<{ disabled: boolean; onIncomplete: (problem: IncompleteWorkflow | null) => void }>) {
  const { go } = useTransitionNavigate();
  const queryClient = useQueryClient();
  const createUpload = useCreateUploadMutation();
  const startRun = useStartProductVideoRunMutation();
  const uploadAll = useMemo(
    () =>
      createMultiUpload({
        requestUpload: createUpload.mutateAsync,
        uploadFile,
      }),
    [createUpload.mutateAsync],
  );

  const [text, setText] = useState<Record<TextFieldName, string>>({
    productName: '',
    description: '',
  });
  const [textError, setTextError] = useState<string | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [imageError, setImageError] = useState<string | null>(null);
  const [phase, setPhase] = useState<MultiUploadPhase | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);

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
    const picked = event.target.files && event.target.files[0] ? [event.target.files[0]] : [];
    setFiles(picked);
    setFailure(null);
    setImageError(firstImageProblem(picked)?.message ?? null);
  };

  const showFailure = (error: unknown) => {
    if (error instanceof MultiUploadError && error.stage === 'upload') {
      setFailure({ kind: 'upload' });
      return;
    }
    const problem = startProblem(error instanceof MultiUploadError ? error.cause : error);
    if (problem.kind === 'field') {
      setImageError(problem.message);
    } else if (problem.kind === 'incomplete') {
      onIncomplete(problem);
    } else {
      setFailure({ kind: 'alert', message: problem.message });
    }
  };

  const clearProblems = () => {
    setFailure(null);
    setTextError(null);
    setImageError(null);
    onIncomplete(null);
  };

  const submitRun = async (body: ProductVideoRunRequest) => {
    let runResponse: RunResponse;
    try {
      runResponse = await startRun.mutateAsync(body);
    } catch (error) {
      setPhase(null);
      const problem = startProblem(error);
      if (problem.kind === 'credits') {
        setFailure({
          kind: 'credits',
          message: problem.message,
          resend: problem.canRunAnyway ? { ...body, acknowledgeCost: true } : null,
        });
      } else {
        showFailure(error);
      }
      return;
    }
    void invalidateBalance(queryClient);
    if (runResponse.id && mounted.current) {
      go(`/runs/${runResponse.id}`);
    }
  };

  const run = async () => {
    clearProblems();
    let uploadIds: string[] | null;
    try {
      uploadIds = await uploadAll(files, setPhase);
    } catch (error) {
      setPhase(null);
      showFailure(error);
      return;
    }
    if (uploadIds === null) {
      return;
    }
    await submitRun({
      productName: text.productName.trim(),
      description: text.description.trim(),
      uploadIds,
      video: { aspectRatio: '9:16' },
    });
  };

  const runAnyway = (body: ProductVideoRunRequest) => {
    if (startRun.isPending) {
      return;
    }
    clearProblems();
    void submitRun(body);
  };

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (phase || startRun.isPending) {
      return;
    }
    const blank = TEXT_FIELDS.find((field) => field.required && text[field.name].trim() === '');
    if (blank) {
      setTextError(`${blank.label} is required.`);
      return;
    }
    if (textError) {
      return;
    }
    const listProblem = firstImageProblem(files);
    if (listProblem) {
      setImageError(listProblem.message);
      return;
    }
    void run();
  };

  const uploadPercent = phase?.kind === 'uploading' ? phase.percent : null;
  const resend = failure?.kind === 'credits' ? failure.resend : null;

  return (
    <form onSubmit={onSubmit} noValidate aria-label="Start a product video run" className="media-start-form">
      {TEXT_FIELDS.map((field) => (
        <div className="field" key={field.id}>
          <label htmlFor={field.id}>
            {field.label}
            {field.required ? ' *' : ''}
          </label>
          {('multiline' in field && field.multiline) ? (
            <textarea
              id={field.id}
              value={text[field.name]}
              maxLength={field.maxLength}
              required={field.required}
              onChange={(event) =>
                setText((current) => ({ ...current, [field.name]: event.target.value }))
              }
            />
          ) : (
            <input
              id={field.id}
              type="text"
              value={text[field.name]}
              maxLength={field.maxLength}
              required={field.required}
              onChange={(event) =>
                setText((current) => ({ ...current, [field.name]: event.target.value }))
              }
            />
          )}
        </div>
      ))}
      {textError && (
        <div className="field-error" role="alert">
          <p>{textError}</p>
        </div>
      )}
      <div className="field">
        <label htmlFor="product-images">Product image (JPG/PNG/WebP, ≤ 10 MB) *</label>
        <input
          id="product-images"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          required
          onChange={onPick}
          aria-invalid={imageError ? true : undefined}
          aria-describedby={imageError ? 'product-images-problem' : undefined}
        />
        {files.length > 0 && (
          <p className="field-hint">1 image selected: {files[0]?.name}</p>
        )}
        {imageError && (
          <div id="product-images-problem" className="field-error" role="alert">
            <p>{imageError}</p>
          </div>
        )}
      </div>
      {uploadPercent !== null && (
        <progress
          className="upload-progress"
          max={100}
          value={uploadPercent}
          aria-label={`Uploading image ${(phase as { index: number }).index + 1}`}
        />
      )}
      {failure?.kind === 'upload' && (
        <div role="alert">
          <p>One of the uploads didn&apos;t finish. Try again.</p>
          <button type="button" onClick={() => void run()}>
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
          busy={startRun.isPending}
          onRunAnyway={resend ? () => runAnyway(resend) : undefined}
        />
      )}
      <button type="submit" aria-busy={phase !== null || startRun.isPending} disabled={disabled}>
        {startButtonLabel(phase)}
      </button>
    </form>
  );
}
