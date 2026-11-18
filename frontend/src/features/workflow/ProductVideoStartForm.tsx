import { UploadSimple } from '@phosphor-icons/react';
import { useQueryClient } from '@tanstack/react-query';
import type { TFunction } from 'i18next';
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type FormEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import type { ProductVideoRunRequest, RunResponse } from '@flow-cortex/api-client';
import { z } from 'zod';
import { useTransitionNavigate } from '../../app/useTransitionNavigate';
import { activeLocale } from '../../lib/i18n';
import { CreditsRefusal } from '../credits/CreditsRefusal';
import { invalidateBalance } from '../credits/useBalance';
import { ProductVideoPreviewPanel } from './ProductVideoPreviewPanel';
import {
  createMultiUpload,
  firstImageProblem,
  MultiUploadError,
  type ImageProblem,
  type MultiUploadPhase,
} from './productVideoUpload';
import { startProblem, type IncompleteWorkflow } from './startProblem';
import { uploadFile } from './uploadFile';
import { useCreateUploadMutation, useStartProductVideoRunMutation } from './useWorkflows';

type Failure =
  | { kind: 'upload' }
  | { kind: 'alert'; message: string }
  | { kind: 'credits'; message: string; resend: ProductVideoRunRequest | null };

type ImageError = { kind: 'check'; problem: ImageProblem } | { kind: 'server'; message: string };

const asCheck = (problem: ImageProblem | null): ImageError | null =>
  problem ? { kind: 'check', problem } : null;

const TEXT_FIELDS = [
  {
    id: 'product-name',
    name: 'productName',
    labelKey: 'workflow.productVideo.field.productName',
    placeholderKey: 'workflow.productVideo.productNamePlaceholder',
    required: true,
    maxLength: 200,
  },
  {
    id: 'product-description',
    name: 'description',
    labelKey: 'workflow.productVideo.field.description',
    placeholderKey: 'workflow.productVideo.descriptionPlaceholder',
    required: true,
    maxLength: 4000,
    multiline: true,
  },
] as const;

type TextFieldName = (typeof TEXT_FIELDS)[number]['name'];

const ProductDraftStateSchema = z.object({
  productDraft: z.object({
    productName: z.string().optional(),
    description: z.string().optional(),
  }),
});

function readInitialDraft(state: unknown): Record<TextFieldName, string> {
  const parsed = ProductDraftStateSchema.safeParse(state);
  if (!parsed.success) return { productName: '', description: '' };
  return {
    productName: parsed.data.productDraft.productName ?? '',
    description: parsed.data.productDraft.description ?? '',
  };
}

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function startButtonLabel(phase: MultiUploadPhase | null, t: TFunction): string {
  if (phase?.kind === 'requesting') {
    return t('workflow.productVideo.preparing');
  }
  if (phase?.kind === 'uploading') {
    return t('workflow.productVideo.uploading', { index: phase.index + 1, total: phase.total });
  }
  return t('workflow.start.button');
}

/**
 * Product Video start form: captures product name, description, and 1 product photo alongside a 9:16 preview.
 */
export function ProductVideoStartForm({
  disabled,
  onIncomplete,
}: Readonly<{
  disabled: boolean;
  onIncomplete: (problem: IncompleteWorkflow | null) => void;
}>) {
  const { t } = useTranslation();
  const { go } = useTransitionNavigate();
  const location = useLocation();
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

  const [text, setText] = useState<Record<TextFieldName, string>>(() =>
    readInitialDraft(location.state),
  );
  const [blankFieldKey, setBlankFieldKey] = useState<string | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const [imageError, setImageError] = useState<ImageError | null>(null);
  const [phase, setPhase] = useState<MultiUploadPhase | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const previewUrl = useMemo(() => {
    const file = files[0];
    if (!file || typeof URL.createObjectURL !== 'function') return null;
    return URL.createObjectURL(file);
  }, [files]);

  useEffect(() => {
    return () => {
      if (previewUrl && typeof URL.revokeObjectURL === 'function') {
        URL.revokeObjectURL(previewUrl);
      }
    };
  }, [previewUrl]);

  const applyPickedFiles = (fileList: FileList | null) => {
    if (phase) return;
    const picked = fileList && fileList[0] ? [fileList[0]] : [];
    setFiles(picked);
    setFailure(null);
    setImageError(asCheck(firstImageProblem(picked)));
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    applyPickedFiles(event.target.files);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragOver(false);
    applyPickedFiles(event.dataTransfer.files);
  };

  const onClearImage = () => {
    if (phase) return;
    setFiles([]);
    setImageError(null);
    setFailure(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const onTextChange = (name: TextFieldName, value: string) => {
    setText((current) => ({ ...current, [name]: value }));
    setBlankFieldKey(null);
  };

  const showFailure = (error: unknown) => {
    if (error instanceof MultiUploadError && error.stage === 'upload') {
      setFailure({ kind: 'upload' });
      return;
    }
    const problem = startProblem(
      error instanceof MultiUploadError ? error.cause : error,
      undefined,
      t,
      activeLocale(),
    );
    if (problem.kind === 'field') {
      setImageError({ kind: 'server', message: problem.message });
    } else if (problem.kind === 'incomplete') {
      onIncomplete(problem);
    } else {
      setFailure({ kind: 'alert', message: problem.message });
    }
  };

  const clearProblems = () => {
    setFailure(null);
    setBlankFieldKey(null);
    setImageError(null);
    onIncomplete(null);
  };

  const submitRun = async (body: ProductVideoRunRequest) => {
    let runResponse: RunResponse;
    try {
      runResponse = await startRun.mutateAsync(body);
    } catch (error) {
      setPhase(null);
      const problem = startProblem(error, undefined, t, activeLocale());
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
      setBlankFieldKey(blank.labelKey);
      return;
    }
    setBlankFieldKey(null);
    const listProblem = firstImageProblem(files);
    if (listProblem) {
      setImageError(asCheck(listProblem));
      return;
    }
    void run();
  };

  const uploadPercent = phase?.kind === 'uploading' ? phase.percent : null;
  const resend = failure?.kind === 'credits' ? failure.resend : null;
  const selectedFile = files[0];

  return (
    <div className="pv-studio-grid">
      <div className="pv-studio-card">
        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('workflow.productVideo.formLabel')}
          className="pv-form"
        >
          {TEXT_FIELDS.map((field) => (
            <div className="pv-field" key={field.id}>
              <div className="pv-field-header">
                <label htmlFor={field.id}>
                  {t(field.labelKey)}
                  {field.required ? ' *' : ''}
                </label>
                <span className="pv-char-count">
                  {t('workflow.productVideo.charCount', {
                    count: text[field.name].length,
                    max: field.maxLength,
                  })}
                </span>
              </div>
              {'multiline' in field && field.multiline ? (
                <textarea
                  id={field.id}
                  className="pv-textarea"
                  value={text[field.name]}
                  maxLength={field.maxLength}
                  required={field.required}
                  placeholder={t(field.placeholderKey)}
                  onChange={(event) => onTextChange(field.name, event.target.value)}
                />
              ) : (
                <input
                  id={field.id}
                  className="pv-input"
                  type="text"
                  value={text[field.name]}
                  maxLength={field.maxLength}
                  required={field.required}
                  placeholder={t(field.placeholderKey)}
                  onChange={(event) => onTextChange(field.name, event.target.value)}
                />
              )}
            </div>
          ))}
          {blankFieldKey && (
            <div className="field-error" role="alert">
              <p>{t('workflow.productVideo.required', { field: t(blankFieldKey) })}</p>
            </div>
          )}
          <div className="pv-field">
            <div className="pv-field-header">
              <label htmlFor="product-images">{t('workflow.productVideo.image')}</label>
            </div>
            <div
              className={dragOver ? 'pv-dropzone pv-dropzone--active' : 'pv-dropzone'}
              onDragOver={(event) => {
                event.preventDefault();
                setDragOver(true);
              }}
              onDragLeave={() => setDragOver(false)}
              onDrop={onDrop}
            >
              <input
                ref={fileInputRef}
                id="product-images"
                className="pv-dropzone-input"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                required
                onChange={onPick}
                aria-invalid={imageError ? true : undefined}
                aria-describedby={imageError ? 'product-images-problem' : undefined}
              />
              {!selectedFile ? (
                <button
                  type="button"
                  className="pv-dropzone-empty"
                  disabled={phase !== null}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <span className="pv-dropzone-icon" aria-hidden="true">
                    <UploadSimple size={24} />
                  </span>
                  <span className="pv-dropzone-title">
                    {t('workflow.productVideo.dropzoneTitle')}
                  </span>
                  <span className="pv-dropzone-hint">
                    {t('workflow.productVideo.dropzoneHint')}
                  </span>
                </button>
              ) : (
                <div className="pv-dropzone-selected">
                  {previewUrl && (
                    <img
                      src={previewUrl}
                      alt={t('workflow.productVideo.imagePreviewAlt')}
                      className="pv-dropzone-thumb"
                    />
                  )}
                  <div className="pv-dropzone-meta">
                    <p className="pv-dropzone-filename">
                      {t('workflow.productVideo.imageSelected', { name: selectedFile.name })}
                    </p>
                    {!imageError && (
                      <p className="pv-dropzone-sub">
                        {t('workflow.productVideo.fileReady', {
                          size: formatBytes(selectedFile.size),
                        })}
                      </p>
                    )}
                  </div>
                  <div className="pv-dropzone-actions">
                    <button
                      type="button"
                      className="pv-secondary-btn"
                      disabled={phase !== null}
                      onClick={() => fileInputRef.current?.click()}
                    >
                      {t('workflow.productVideo.changeImage')}
                    </button>
                    <button
                      type="button"
                      className="pv-secondary-btn"
                      disabled={phase !== null}
                      onClick={onClearImage}
                    >
                      {t('workflow.productVideo.removeImage')}
                    </button>
                  </div>
                </div>
              )}
            </div>
            {imageError && (
              <div id="product-images-problem" className="field-error" role="alert">
                <p>
                  {imageError.kind === 'server'
                    ? imageError.message
                    : t(imageError.problem.messageKey, imageError.problem.values ?? {})}
                </p>
              </div>
            )}
          </div>
          {uploadPercent !== null && (
            <progress
              className="upload-progress"
              max={100}
              value={uploadPercent}
              aria-label={t('workflow.productVideo.uploadingImage', {
                index: (phase as { index: number }).index + 1,
              })}
            />
          )}
          {failure?.kind === 'upload' && (
            <div role="alert">
              <p>{t('workflow.productVideo.uploadsUnfinished')}</p>
              <button type="button" onClick={() => void run()}>
                {t('workflow.start.retry')}
              </button>
            </div>
          )}
          {failure?.kind === 'alert' && (
            <div role="alert">
              <p>{failure.message}</p>
              <p>{t('workflow.start.nextAction')}</p>
            </div>
          )}
          {failure?.kind === 'credits' && (
            <CreditsRefusal
              message={failure.message}
              busy={startRun.isPending}
              onRunAnyway={resend ? () => runAnyway(resend) : undefined}
            />
          )}
          <div className="pv-submit-row">
            <button
              type="submit"
              className="primary-button"
              aria-busy={phase !== null || startRun.isPending}
              disabled={disabled}
            >
              {startButtonLabel(phase, t)}
            </button>
          </div>
        </form>
      </div>
      <ProductVideoPreviewPanel
        productName={text.productName}
        description={text.description}
        previewUrl={previewUrl}
      />
    </div>
  );
}
