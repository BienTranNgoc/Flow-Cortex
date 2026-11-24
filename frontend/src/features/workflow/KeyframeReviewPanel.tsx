import type { RunStepResponse } from '@flow-cortex/api-client';
import { ArrowsClockwise, Check } from '@phosphor-icons/react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useProductVideoTemplatesQuery } from './productVideoPresets';
import { startProblem } from './startProblem';
import { useOutputUrlMutation, useRegenerateKeyframeMutation } from './useWorkflows';

const MAX_SCENE_NOTE = 500;

interface KeyframeReviewPanelProps {
  runId: string;
  keyframeStep: RunStepResponse;
  onApprove: () => void;
  onReject: () => void;
  approvePending: boolean;
  rejectPending: boolean;
  approveError?: string | undefined;
  rejectError?: string | undefined;
}

export function KeyframeReviewPanel({
  runId,
  keyframeStep,
  onApprove,
  onReject,
  approvePending,
  rejectPending,
  approveError,
  rejectError,
}: Readonly<KeyframeReviewPanelProps>) {
  const { t } = useTranslation();
  const catalogQuery = useProductVideoTemplatesQuery();
  const backgroundPresets = catalogQuery.data?.backgroundPresets ?? [];

  const artifact = (keyframeStep.outputs ?? []).find(
    (output) =>
      output.mime?.startsWith('image/') ||
      /\.(jpe?g|png|webp)$/i.test(output.name),
  );
  const stepOrder = keyframeStep.stepOrder ?? 5;
  const attempt = keyframeStep.attempt ?? 1;

  const previewMutation = useOutputUrlMutation(runId, stepOrder, artifact?.name ?? '');
  const { mutate: requestPreviewUrl } = previewMutation;
  const regenerate = useRegenerateKeyframeMutation(runId);

  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [imageLoaded, setImageLoaded] = useState(false);
  const [previewError, setPreviewError] = useState(false);
  const [backgroundPresetId, setBackgroundPresetId] = useState<string | null>(null);
  const [sceneNote, setSceneNote] = useState('');

  useEffect(() => {
    setImageLoaded(false);
    if (!artifact?.name) {
      setImageUrl(null);
      return;
    }
    let active = true;
    setPreviewError(false);
    requestPreviewUrl(undefined, {
      onSuccess: ({ url }) => {
        if (active) setImageUrl(url);
      },
      onError: () => {
        if (active) setPreviewError(true);
      },
    });
    return () => {
      active = false;
    };
  }, [requestPreviewUrl, runId, stepOrder, artifact?.name, attempt]);

  const busy = approvePending || rejectPending || regenerate.isPending;
  const approveDisabled = busy || !imageLoaded;

  const submitRegenerate = (acknowledgeCost: boolean) => {
    if (busy) return;
    const trimmedNote = sceneNote.trim();
    regenerate.mutate({
      ...(backgroundPresetId ? { backgroundPresetId } : {}),
      ...(trimmedNote ? { sceneNote: trimmedNote } : {}),
      acknowledgeCost,
    });
  };

  const regenProblem = regenerate.isError
    ? startProblem(regenerate.error, t('workflow.keyframeReview.regenerateFailed'), t)
    : null;
  const canConfirmCost = regenProblem?.kind === 'credits' && regenProblem.canRunAnyway;
  const regenAlertMessage =
    regenProblem && !canConfirmCost
      ? regenProblem.kind === 'incomplete'
        ? regenProblem.summary
        : regenProblem.message
      : null;

  return (
    <section className="approval-panel pv-keyframe-review" aria-label={t('workflow.keyframeReview.region')}>
      <div className="pv-keyframe-stage">
        <div className="pv-keyframe-frame">
          {imageUrl ? (
            <img
              src={imageUrl}
              alt={t('workflow.keyframeReview.imageAlt', { attempt })}
              className="pv-keyframe-img"
              onLoad={() => setImageLoaded(true)}
              onError={() => {
                setImageLoaded(false);
                setImageUrl(null);
                setPreviewError(true);
              }}
            />
          ) : (
            <div className="pv-keyframe-placeholder">
              {previewMutation.isPending ? (
                <span>{t('workflow.keyframeReview.loadingImage')}</span>
              ) : (
                <button
                  type="button"
                  className="pv-secondary-btn"
                  disabled={!artifact?.name}
                  onClick={() => {
                    if (!artifact?.name) return;
                    setImageLoaded(false);
                    setPreviewError(false);
                    requestPreviewUrl(undefined, {
                      onSuccess: ({ url }) => setImageUrl(url),
                      onError: () => setPreviewError(true),
                    });
                  }}
                >
                  {t('workflow.keyframeReview.reloadImage')}
                </button>
              )}
            </div>
          )}
        </div>
        <p className="pv-keyframe-meta">
          {t('workflow.keyframeReview.meta', { attempt })}
        </p>
        {previewError && (
          <p role="alert" className="approval-error">
            {t('workflow.keyframeReview.imageLoadError')}
          </p>
        )}
      </div>

      <div className="pv-keyframe-controls">
        <div className="pv-field">
          <div className="pv-field-header">
            <span className="pv-keyframe-label">{t('workflow.keyframeReview.backgroundLabel')}</span>
          </div>
          <div className="pv-keyframe-presets" role="group" aria-label={t('workflow.keyframeReview.backgroundLabel')}>
            {backgroundPresets.map((preset) => {
              const selected = backgroundPresetId === preset.id;
              return (
                <button
                  key={preset.id}
                  type="button"
                  className={selected ? 'pv-keyframe-preset pv-keyframe-preset--selected' : 'pv-keyframe-preset'}
                  aria-pressed={selected}
                  disabled={busy}
                  onClick={() => setBackgroundPresetId(selected ? null : preset.id)}
                >
                  <img src={preset.imageUrl} alt="" className="pv-keyframe-preset-thumb" />
                  <span className="pv-keyframe-preset-name">{preset.label}</span>
                  {selected && <Check size={14} weight="bold" aria-hidden="true" className="pv-keyframe-preset-check" />}
                </button>
              );
            })}
          </div>
        </div>

        <div className="pv-field">
          <div className="pv-field-header">
            <label htmlFor="keyframe-scene-note">{t('workflow.keyframeReview.sceneNoteLabel')}</label>
            <span className="pv-char-count">
              {sceneNote.length} / {MAX_SCENE_NOTE}
            </span>
          </div>
          <textarea
            id="keyframe-scene-note"
            className="pv-textarea pv-keyframe-note"
            rows={2}
            maxLength={MAX_SCENE_NOTE}
            value={sceneNote}
            disabled={busy}
            placeholder={t('workflow.keyframeReview.sceneNotePlaceholder')}
            onChange={(event) => setSceneNote(event.target.value)}
          />
        </div>

        <div className="pv-keyframe-regen-row">
          <button
            type="button"
            className="pv-secondary-btn"
            disabled={busy}
            aria-busy={regenerate.isPending}
            onClick={() => submitRegenerate(false)}
          >
            <ArrowsClockwise size={16} aria-hidden="true" />
            {regenerate.isPending
              ? t('workflow.keyframeReview.regenerating')
              : t('workflow.keyframeReview.regenerate')}
          </button>
        </div>

        {canConfirmCost && regenProblem.kind === 'credits' && (
          <div className="pv-keyframe-confirm" role="alert">
            <span>{regenProblem.message}</span>
            <button
              type="button"
              className="pv-secondary-btn"
              disabled={busy}
              onClick={() => submitRegenerate(true)}
            >
              {t('workflow.keyframeReview.confirmCostAction')}
            </button>
          </div>
        )}

        {regenAlertMessage && (
          <p role="alert" className="approval-error">
            {regenAlertMessage}
          </p>
        )}

        <hr className="pv-keyframe-divider" />

        <div className="approval-actions">
          <button
            type="button"
            className="primary-button"
            disabled={approveDisabled}
            aria-busy={approvePending}
            onClick={() => {
              if (!approveDisabled) onApprove();
            }}
          >
            {approvePending
              ? t('workflow.approval.approving')
              : t('workflow.keyframeReview.approveAndGenerate')}
          </button>
          <button
            type="button"
            disabled={busy}
            aria-busy={rejectPending}
            onClick={() => {
              if (!busy) onReject();
            }}
          >
            {rejectPending ? t('workflow.approval.rejecting') : t('workflow.approval.reject')}
          </button>
        </div>

        {approveError && (
          <p role="alert" className="approval-error">
            {approveError}
          </p>
        )}
        {rejectError && (
          <p role="alert" className="approval-error">
            {rejectError}
          </p>
        )}
      </div>
    </section>
  );
}
