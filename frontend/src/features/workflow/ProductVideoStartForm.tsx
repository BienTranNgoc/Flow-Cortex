import { ApiError } from '@flow-cortex/api-client';
import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  createMultiUpload,
  firstImageProblem,
  MultiUploadError,
  type MultiUploadPhase,
} from './productVideoUpload';
import { uploadFile } from './uploadFile';
import {
  useCreateUploadMutation,
  useStartProductVideoRunMutation,
} from './useWorkflows';

const NEXT_ACTION = 'Try again, or go back to the Workflows list.';

type Failure = { kind: 'upload' } | { kind: 'alert'; message: string };

const TEXT_FIELDS = [
  { id: 'product-name', name: 'productName', label: 'Product name', required: true, maxLength: 200 },
  { id: 'product-description', name: 'description', label: 'Description', required: true, maxLength: 4000, multiline: true },
  { id: 'product-cta', name: 'cta', label: 'Call to action', required: true, maxLength: 500 },
  { id: 'product-price', name: 'price', label: 'Price (optional)', required: false, maxLength: 200 },
  { id: 'product-promotion', name: 'promotion', label: 'Promotion (optional)', required: false, maxLength: 500 },
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
 * validated, the schema is the API's, the form just refuses obvious blanks).
 */
export function ProductVideoStartForm({
  disabled,
}: Readonly<{ disabled: boolean }>) {
  const navigate = useNavigate();
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
    cta: '',
    price: '',
    promotion: '',
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
    const picked = event.target.files ? Array.from(event.target.files) : [];
    setFiles(picked);
    setFailure(null);
    setImageError(firstImageProblem(picked)?.message ?? null);
  };

  const showFailure = (error: unknown) => {
    if (error instanceof MultiUploadError && error.stage === 'upload') {
      setFailure({ kind: 'upload' });
      return;
    }
    const cause = error instanceof MultiUploadError ? error.cause : error;
    const problem = cause instanceof ApiError ? cause.problem : undefined;
    const fieldMessage =
      problem?.errors?.uploadIds ?? problem?.errors?.uploadId ?? problem?.errors?.size;
    if (fieldMessage) {
      setImageError(fieldMessage);
      return;
    }
    setFailure({ kind: 'alert', message: problem?.detail ?? 'Could not start the run.' });
  };

  const run = async () => {
    setFailure(null);
    setTextError(null);
    setImageError(null);
    try {
      const uploadIds = await uploadAll(files, setPhase);
      if (uploadIds === null) {
        return;
      }
      const runResponse = await startRun.mutateAsync({
        productName: text.productName.trim(),
        description: text.description.trim(),
        cta: text.cta.trim(),
        ...(text.price.trim() ? { price: text.price.trim() } : {}),
        ...(text.promotion.trim() ? { promotion: text.promotion.trim() } : {}),
        uploadIds,
        video: { aspectRatio: '9:16' },
      });
      if (runResponse.id && mounted.current) {
        navigate(`/runs/${runResponse.id}`);
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
        <label htmlFor="product-images">Product images (1–10, JPG/PNG/WebP, ≤ 10 MB each) *</label>
        <input
          id="product-images"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          required
          onChange={onPick}
          aria-invalid={imageError ? true : undefined}
          aria-describedby={imageError ? 'product-images-problem' : undefined}
        />
        {files.length > 0 && (
          <p className="field-hint">{files.length} image{files.length === 1 ? '' : 's'} selected</p>
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
      <button type="submit" aria-busy={phase !== null} disabled={disabled}>
        {startButtonLabel(phase)}
      </button>
    </form>
  );
}
