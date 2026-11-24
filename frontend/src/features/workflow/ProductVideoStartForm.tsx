import { Plus, UploadSimple, X } from '@phosphor-icons/react';
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
  MAX_IMAGES,
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

const DraftImageSchema = z.object({
  name: z.string().min(1),
  mimeType: z.string().min(1),
  url: z.string().url(),
});

type DraftImage = z.infer<typeof DraftImageSchema>;

const ProductDraftStateSchema = z.object({
  productDraft: z.object({
    productName: z.string().optional(),
    description: z.string().optional(),
    images: z.array(DraftImageSchema).optional(),
  }),
});

const MIME_TO_EXT: Record<string, string> = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
};

function normalizeDraftFileName(name: string, mimeType: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.png') || lower.endsWith('.webp')) {
    return name;
  }
  const ext = MIME_TO_EXT[mimeType.toLowerCase()] ?? '.jpg';
  return `${name}${ext}`;
}

function readInitialDraft(state: unknown): Record<TextFieldName, string> {
  const parsed = ProductDraftStateSchema.safeParse(state);
  if (!parsed.success) return { productName: '', description: '' };
  return {
    productName: parsed.data.productDraft.productName ?? '',
    description: parsed.data.productDraft.description ?? '',
  };
}

function readInitialImages(state: unknown): DraftImage[] {
  const parsed = ProductDraftStateSchema.safeParse(state);
  if (!parsed.success || !parsed.data.productDraft.images) return [];
  return parsed.data.productDraft.images.slice(0, MAX_IMAGES);
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
  const [initialImages] = useState<DraftImage[]>(() => readInitialImages(location.state));
  const [restoringImages, setRestoringImages] = useState(initialImages.length > 0);
  const [unrestoredImages, setUnrestoredImages] = useState(0);
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

  useEffect(() => {
    if (initialImages.length === 0) return;
    const controller = new AbortController();
    void (async () => {
      const loaded: File[] = [];
      let failed = 0;
      for (const item of initialImages) {
        try {
          const response = await fetch(item.url, { signal: controller.signal });
          if (!response.ok) {
            failed += 1;
            continue;
          }
          const blob = await response.blob();
          const mime = blob.type || item.mimeType;
          const fileName = normalizeDraftFileName(item.name, mime);
          loaded.push(new File([blob], fileName, { type: mime }));
        } catch {
          if (controller.signal.aborted) return;
          failed += 1;
        }
      }
      if (controller.signal.aborted) return;
      if (loaded.length > 0) {
        setFiles((current) => (current.length > 0 ? current : loaded));
      }
      setUnrestoredImages(failed);
      setRestoringImages(false);
    })();
    return () => controller.abort();
  }, [initialImages]);

  const previewUrls = useMemo(() => {
    if (typeof URL.createObjectURL !== 'function') return [] as string[];
    return files.map((file) => URL.createObjectURL(file));
  }, [files]);

  useEffect(() => {
    return () => {
      if (typeof URL.revokeObjectURL === 'function') {
        for (const url of previewUrls) {
          URL.revokeObjectURL(url);
        }
      }
    };
  }, [previewUrls]);

  const previewUrl = previewUrls[0] ?? null;

  const applyPickedFiles = (fileList: FileList | null) => {
    if (phase || !fileList || fileList.length === 0) return;
    const incoming = Array.from(fileList);
    const seen = new Set(files.map((f) => `${f.name}:${f.size}:${f.lastModified}`));
    const merged = [...files];
    for (const file of incoming) {
      const key = `${file.name}:${file.size}:${file.lastModified}`;
      if (!seen.has(key)) {
        seen.add(key);
        merged.push(file);
      }
    }
    setFiles(merged);
    setFailure(null);
    setImageError(asCheck(firstImageProblem(merged)));
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    applyPickedFiles(event.target.files);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragOver(false);
    applyPickedFiles(event.dataTransfer.files);
  };

  const onRemoveImage = (index: number) => {
    if (phase) return;
    const next = files.filter((_, i) => i !== index);
    setFiles(next);
    setFailure(null);
    setImageError(next.length > 0 ? asCheck(firstImageProblem(next)) : null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
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
    if (phase || startRun.isPending || restoringImages) {
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
  const imageProblemId = imageError ? 'product-images-problem' : undefined;

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
              <span className="pv-char-count">
                {t('workflow.productVideo.imageCount', {
                  count: files.length,
                  max: MAX_IMAGES,
                })}
              </span>
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
                multiple
                accept="image/jpeg,image/png,image/webp"
                required
                tabIndex={-1}
                onChange={onPick}
                aria-invalid={imageError ? true : undefined}
                aria-describedby={imageProblemId}
              />
              {files.length === 0 ? (
                <button
                  type="button"
                  className="pv-dropzone-empty"
                  disabled={phase !== null}
                  aria-describedby={imageProblemId}
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
                <div className="pv-image-list-wrap">
                  <ul className="pv-image-list">
                    {files.map((file, index) => (
                      <li className="pv-image-item" key={`${file.name}-${file.size}-${index}`}>
                        <div className="pv-image-thumb-wrap">
                          {previewUrls[index] && (
                            <img
                              src={previewUrls[index]}
                              alt={t('workflow.productVideo.imagePreviewAlt')}
                              className="pv-dropzone-thumb"
                            />
                          )}
                          <span className="pv-image-badge">{index + 1}</span>
                        </div>
                        <div className="pv-dropzone-meta">
                          <p className="pv-dropzone-filename">{file.name}</p>
                          <p className="pv-dropzone-sub">
                            {t('workflow.productVideo.fileReady', {
                              size: formatBytes(file.size),
                            })}
                          </p>
                        </div>
                        <button
                          type="button"
                          className="pv-image-remove"
                          disabled={phase !== null}
                          onClick={() => onRemoveImage(index)}
                          aria-label={t('workflow.productVideo.removeImageAt', {
                            name: file.name,
                          })}
                        >
                          <X size={16} aria-hidden="true" />
                        </button>
                      </li>
                    ))}
                  </ul>
                  <div className="pv-dropzone-actions">
                    {files.length < MAX_IMAGES && (
                      <button
                        type="button"
                        className="pv-secondary-btn"
                        disabled={phase !== null}
                        aria-describedby={imageProblemId}
                        onClick={() => fileInputRef.current?.click()}
                      >
                        <Plus size={16} aria-hidden="true" />
                        {t('workflow.productVideo.addMoreImages')}
                      </button>
                    )}
                    <button
                      type="button"
                      className="pv-secondary-btn"
                      disabled={phase !== null}
                      onClick={onClearImage}
                    >
                      {files.length > 1
                        ? t('workflow.productVideo.clearAllImages')
                        : t('workflow.productVideo.removeImage')}
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
            {restoringImages && (
              <p className="field-hint" role="status">
                {t('workflow.productVideo.restoringImages')}
              </p>
            )}
            {unrestoredImages > 0 && (
              <div className="field-error" role="alert">
                <p>{t('workflow.productVideo.restoreFailed', { count: unrestoredImages })}</p>
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
              aria-busy={phase !== null || startRun.isPending || restoringImages}
              disabled={disabled || restoringImages}
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
