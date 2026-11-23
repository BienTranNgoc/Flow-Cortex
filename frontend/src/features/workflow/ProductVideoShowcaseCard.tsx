import type { RunStepOutputResponse, RunStepResponse } from '@flow-cortex/api-client';
import { CheckCircle, DownloadSimple, Play } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { openInCurrentTab } from './browser';
import { parseVideoOutput } from './productVideoSchemas';
import { useOutputUrlMutation } from './useWorkflows';

interface ProductVideoShowcaseCardProps {
  runId: string;
  workflowId?: string | undefined;
  renderStep: RunStepResponse;
  artifact: RunStepOutputResponse;
}

/** Hero showcase presenting the single final MP4 ad video with inline 9:16 playback and download. */
export function ProductVideoShowcaseCard({
  runId,
  workflowId,
  renderStep,
  artifact,
}: Readonly<ProductVideoShowcaseCardProps>) {
  const { t } = useTranslation();
  const stepOrder = renderStep.stepOrder ?? 7;
  const previewMutation = useOutputUrlMutation(runId, stepOrder, artifact.name);
  const downloadMutation = useOutputUrlMutation(runId, stepOrder, artifact.name);

  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState(false);
  const [videoRetried, setVideoRetried] = useState(false);
  const openingRef = useRef(false);

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
        </div>
      </div>
    </section>
  );
}
