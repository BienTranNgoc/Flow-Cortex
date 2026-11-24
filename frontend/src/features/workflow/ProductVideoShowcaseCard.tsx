import type { RunStepOutputResponse, RunStepResponse } from '@flow-cortex/api-client';
import { ArrowsClockwise, CheckCircle, DownloadSimple, Play } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { openInCurrentTab } from './browser';
import { parseVideoOutput } from './productVideoSchemas';
import { startProblem } from './startProblem';
import { useOutputUrlMutation, useRedoProductVideoMutation } from './useWorkflows';

const MAX_REDO_NOTE = 500;

interface RedoTarget {
  stepOrder: number;
  label: string;
  supportsNote: boolean;
}

interface ProductVideoShowcaseCardProps {
  runId: string;
  workflowId?: string | undefined;
  renderStep: RunStepResponse;
  artifact: RunStepOutputResponse;
  steps?: readonly RunStepResponse[] | undefined;
}

/** Hero showcase presenting the single final MP4 ad video with inline 9:16 playback and download. */
export function ProductVideoShowcaseCard({
  runId,
  workflowId,
  renderStep,
  artifact,
  steps = [],
}: Readonly<ProductVideoShowcaseCardProps>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const stepOrder = renderStep.stepOrder ?? 7;
  const previewMutation = useOutputUrlMutation(runId, stepOrder, artifact.name);
  const downloadMutation = useOutputUrlMutation(runId, stepOrder, artifact.name);
  const redoMutation = useRedoProductVideoMutation(runId);

  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState(false);
  const [videoRetried, setVideoRetried] = useState(false);
  const [activeRedo, setActiveRedo] = useState<RedoTarget | null>(null);
  const [redoNote, setRedoNote] = useState('');
  const openingRef = useRef(false);

  const videoSteps = steps.filter(
    (s) => s.type === 'generate_video' && s.stepOrder !== undefined,
  );
  const lipsyncStep = steps.find(
    (s) => s.type === 'lipsync_video' && s.stepOrder !== undefined,
  );
  const redoTargets: RedoTarget[] = [
    ...videoSteps.map((s, idx) => ({
      stepOrder: s.stepOrder!,
      label: t('workflow.redo.scene', { n: idx + 1 }),
      supportsNote: true,
    })),
    ...(lipsyncStep
      ? [
          {
            stepOrder: lipsyncStep.stepOrder!,
            label: t('workflow.redo.lipsync'),
            supportsNote: false,
          },
        ]
      : []),
  ];

  const submitRedo = (acknowledgeCost: boolean) => {
    if (!activeRedo || redoMutation.isPending) return;
    const trimmed = activeRedo.supportsNote ? redoNote.trim() : '';
    redoMutation.mutate(
      {
        redoSteps: [activeRedo.stepOrder],
        ...(trimmed ? { sceneNote: trimmed } : {}),
        acknowledgeCost,
      },
      {
        onSuccess: (created) => {
          if (created.id) {
            const targetWf = created.workflowId ?? workflowId;
            navigate(targetWf ? `/workflows/${targetWf}/runs/${created.id}` : `/runs/${created.id}`);
          }
        },
      },
    );
  };

  const redoProblem = redoMutation.isError
    ? startProblem(redoMutation.error, t('workflow.redo.failed'), t)
    : null;
  const canConfirmRedoCost = redoProblem?.kind === 'credits' && redoProblem.canRunAnyway;
  const redoAlertMessage =
    redoProblem && !canConfirmRedoCost
      ? redoProblem.kind === 'incomplete'
        ? redoProblem.summary
        : redoProblem.message
      : null;

  const meta = parseVideoOutput(renderStep.output);
  const aspectRatio = meta?.aspectRatio ?? '9:16';
  const durationSeconds = meta?.durationSeconds ?? 10;
  const width = meta?.width ?? 480;
  const height = meta?.height ?? 854;

  const loadPreview = () => {
    setPreviewError(false);
    previewMutation.mutate(undefined, {
      onSuccess: ({ url }) => setVideoUrl(url),
      onError: () => setPreviewError(true),
    });
  };

  const { mutate: requestPreviewUrl } = previewMutation;

  useEffect(() => {
    let active = true;
    requestPreviewUrl(undefined, {
      onSuccess: ({ url }) => {
        if (active) setVideoUrl(url);
      },
      onError: () => {
        if (active) setPreviewError(true);
      },
    });
    return () => {
      active = false;
    };
  }, [requestPreviewUrl, runId, stepOrder, artifact.name]);

  const onVideoError = () => {
    if (!videoRetried) {
      setVideoRetried(true);
      setVideoUrl(null);
      loadPreview();
      return;
    }
    setVideoUrl(null);
    setPreviewError(true);
  };

  const onDownloadFinal = () => {
    if (downloadMutation.isPending || openingRef.current) return;
    openingRef.current = true;
    downloadMutation.mutate(undefined, {
      onSuccess: ({ url }) => openInCurrentTab(url),
      onSettled: () => {
        openingRef.current = false;
      },
    });
  };

  return (
    <section className="pv-showcase" aria-label={t('workflow.download.finalShowcaseHeading')}>
      <div className="pv-showcase-grid">
        <div className="pv-showcase-stage">
          {videoUrl ? (
            <video
              controls
              playsInline
              preload="metadata"
              src={videoUrl}
              className="pv-showcase-video"
              aria-label={t('workflow.download.adVideo', { name: artifact.name })}
              onError={onVideoError}
            >
              <track kind="captions" />
              {t('workflow.download.cannotPlay')}
            </video>
          ) : (
            <div className="pv-showcase-placeholder">
              <button
                type="button"
                className="pv-secondary-btn"
                disabled={previewMutation.isPending}
                onClick={loadPreview}
              >
                <Play size={16} aria-hidden="true" />
                {previewMutation.isPending
                  ? t('workflow.download.loadingVideo')
                  : t('workflow.download.watchVideo')}
              </button>
              {previewError && (
                <p role="alert" className="run-step-download-error">
                  {t('workflow.download.playFailed')}
                </p>
              )}
            </div>
          )}
        </div>

        <div className="pv-showcase-content">
          <span className="pv-showcase-badge">
            <CheckCircle size={15} aria-hidden="true" />
            {t('workflow.download.finalShowcaseBadge')}
          </span>
          <h2 className="pv-showcase-title">{t('workflow.download.finalShowcaseHeading')}</h2>

          <dl className="pv-showcase-specs">
            <div className="pv-spec-item">
              <dt>{t('workflow.download.specAspect')}</dt>
              <dd>{t('workflow.download.specAspectVal', { ratio: aspectRatio })}</dd>
            </div>
            <div className="pv-spec-item">
              <dt>{t('workflow.download.specResolution')}</dt>
              <dd>{t('workflow.download.specResolutionVal', { width, height })}</dd>
            </div>
            <div className="pv-spec-item">
              <dt>{t('workflow.download.specDuration')}</dt>
              <dd>{t('workflow.download.specDurationVal', { seconds: durationSeconds })}</dd>
            </div>
            <div className="pv-spec-item">
              <dt>{t('workflow.download.specFormat')}</dt>
              <dd>{t('workflow.download.specFormatVal')}</dd>
            </div>
          </dl>

          <div className="pv-showcase-actions">
            <button
              type="button"
              className="primary-button"
              disabled={downloadMutation.isPending}
              onClick={onDownloadFinal}
            >
              <DownloadSimple size={18} aria-hidden="true" />
              {downloadMutation.isPending
                ? t('workflow.download.gettingFile')
                : t('workflow.download.downloadFinalVideo')}
            </button>
            {workflowId && (
              <Link className="pv-secondary-btn" to={`/workflows/${workflowId}`}>
                {t('workflow.download.createAnother')}
              </Link>
            )}
          </div>

          {downloadMutation.isError && (
            <p role="alert" className="run-step-download-error">
              {t('workflow.download.linkError')}
            </p>
          )}

          {redoTargets.length > 0 && (
            <div className="pv-redo-bar">
              <div className="pv-redo-actions">
                {redoTargets.map((target) => {
                  const selected = activeRedo?.stepOrder === target.stepOrder;
                  return (
                    <button
                      key={target.stepOrder}
                      type="button"
                      className={selected ? 'pv-secondary-btn pv-redo-btn--active' : 'pv-secondary-btn'}
                      aria-pressed={selected}
                      disabled={redoMutation.isPending}
                      onClick={() => {
                        redoMutation.reset();
                        setRedoNote('');
                        setActiveRedo(selected ? null : target);
                      }}
                    >
                      <ArrowsClockwise size={15} aria-hidden="true" />
                      {target.label}
                    </button>
                  );
                })}
              </div>

              {activeRedo && (
                <div className="pv-redo-panel">
                  {activeRedo.supportsNote && (
                    <div className="pv-field">
                      <div className="pv-field-header">
                        <label htmlFor="pv-redo-note">{t('workflow.redo.noteLabel')}</label>
                        <span className="pv-char-count">
                          {redoNote.length} / {MAX_REDO_NOTE}
                        </span>
                      </div>
                      <textarea
                        id="pv-redo-note"
                        className="pv-textarea pv-keyframe-note"
                        rows={2}
                        maxLength={MAX_REDO_NOTE}
                        value={redoNote}
                        disabled={redoMutation.isPending}
                        placeholder={t('workflow.redo.notePlaceholder')}
                        onChange={(event) => setRedoNote(event.target.value)}
                      />
                    </div>
                  )}

                  {canConfirmRedoCost && redoProblem.kind === 'credits' && (
                    <div className="pv-keyframe-confirm" role="alert">
                      <span>{redoProblem.message}</span>
                      <button
                        type="button"
                        className="pv-secondary-btn"
                        disabled={redoMutation.isPending}
                        onClick={() => submitRedo(true)}
                      >
                        {t('workflow.redo.confirmCostAction')}
                      </button>
                    </div>
                  )}

                  {redoAlertMessage && (
                    <p role="alert" className="approval-error">
                      {redoAlertMessage}
                    </p>
                  )}

                  <div className="pv-redo-panel-actions">
                    <button
                      type="button"
                      className="primary-button"
                      disabled={redoMutation.isPending}
                      aria-busy={redoMutation.isPending}
                      onClick={() => submitRedo(false)}
                    >
                      {redoMutation.isPending ? t('workflow.redo.running') : t('workflow.redo.confirm')}
                    </button>
                    <button
                      type="button"
                      className="pv-secondary-btn"
                      disabled={redoMutation.isPending}
                      onClick={() => {
                        redoMutation.reset();
                        setActiveRedo(null);
                      }}
                    >
                      {t('workflow.redo.cancel')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
