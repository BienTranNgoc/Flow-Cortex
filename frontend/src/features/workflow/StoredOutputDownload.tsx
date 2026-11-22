import type { RunStepOutputResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AudioWaveform } from './AudioWaveform';
import { openInCurrentTab } from './browser';
import { useOutputUrlMutation } from './useWorkflows';

interface StoredOutputDownloadProps {
  runId: string;
  stepOrder: number;
  output: RunStepOutputResponse;
  allowDownload?: boolean | undefined;
  downloadLabel?: string | undefined;
  previewNote?: string | undefined;
}

function label(output: RunStepOutputResponse, t: TFunction): string {
  if (output.mime === 'audio/wav') {
    return t('workflow.download.extractedAudio');
  }
  return t('workflow.download.named', { name: output.name });
}

function isVideoOutput(output: RunStepOutputResponse): boolean {
  return Boolean(output.mime?.startsWith('video/') || output.name.endsWith('.mp4'));
}

function isAudioOutput(output: RunStepOutputResponse): boolean {
  return Boolean(
    output.mime?.startsWith('audio/') ||
    output.name.endsWith('.wav') ||
    output.name.endsWith('.mp3'),
  );
}

/** Step artifact viewer and downloader, supporting preview-only intermediate outputs for Ad Video. */
export function StoredOutputDownload({
  runId,
  stepOrder,
  output,
  allowDownload = true,
  downloadLabel,
  previewNote,
}: Readonly<StoredOutputDownloadProps>) {
  const { t } = useTranslation();
  const outputUrl = useOutputUrlMutation(runId, stepOrder, output.name);
  const [mediaUrl, setMediaUrl] = useState<string | null>(null);
  const [showMedia, setShowMedia] = useState(false);
  const [mediaRetried, setMediaRetried] = useState(false);
  const [mediaFailed, setMediaFailed] = useState(false);

  const isVideo = isVideoOutput(output);
  const isAudio = isAudioOutput(output);
  const opening = useRef(0);

  const download = () => {
    if (!allowDownload || outputUrl.isPending) {
      return;
    }
    outputUrl.mutate(undefined, {
      onSuccess: ({ url }) => openInCurrentTab(url),
    });
  };

  const loadMedia = () => {
    const current = opening.current;
    outputUrl.mutate(undefined, {
      onSuccess: ({ url }) => {
        if (opening.current !== current) {
          return;
        }
        setMediaUrl(url);
        setShowMedia(true);
      },
    });
  };

  const toggleMedia = () => {
    if (showMedia) {
      opening.current += 1;
      setShowMedia(false);
      setMediaUrl(null);
      return;
    }
    if (outputUrl.isPending) {
      return;
    }
    setMediaRetried(false);
    setMediaFailed(false);
    loadMedia();
  };

  // Recover once on an expired presigned media URL before surfacing a playback error.
  const retryMedia = () => {
    if (mediaRetried || outputUrl.isPending) {
      setMediaFailed(true);
      return;
    }
    setMediaRetried(true);
    loadMedia();
  };

  if (isAudio && !allowDownload) {
    return (
      <div className="pv-media-card">
        <div className="video-artifact-header">
          <div className="video-artifact-title">
            <span>{t('workflow.download.generatedSpeechTitle', { name: output.name })}</span>
          </div>
        </div>

        {showMedia && mediaUrl && (
          <audio
            className="pv-audio-player"
            controls
            controlsList="nodownload"
            autoPlay
            src={mediaUrl}
            onError={retryMedia}
          >
            <track kind="captions" />
            {t('workflow.download.cannotPlayAudio')}
          </audio>
        )}
        {showMedia && mediaFailed && (
          <p role="alert" className="run-step-download-error">
            {t('workflow.download.audioFailed')}
          </p>
        )}

        <div className="video-artifact-actions run-step-downloads">
          <button
            type="button"
            className="video-toggle-btn"
            onClick={toggleMedia}
            aria-disabled={outputUrl.isPending}
            aria-busy={outputUrl.isPending}
          >
            <span>
              {showMedia
                ? t('workflow.download.hideAudio')
                : outputUrl.isPending
                  ? t('workflow.download.loadingAudio')
                  : t('workflow.download.listenPreview')}
            </span>
          </button>
        </div>

        <p className="pv-intermediate-note">
          {previewNote ?? t('workflow.download.intermediateAudioNote')}
        </p>

        {outputUrl.isError && (
          <p role="alert" className="run-step-download-error">
            {t('workflow.download.linkError')}
          </p>
        )}
      </div>
    );
  }

  if (isVideo) {
    const watchText = allowDownload
      ? t('workflow.download.watchVideo')
      : t('workflow.download.watchClip');
    const hideText = allowDownload
      ? t('workflow.download.hideVideo')
      : t('workflow.download.hideClip');

    return (
      <div className="video-artifact-card">
        <div className="video-artifact-header">
          <div className="video-artifact-title">
            <svg
              className="video-icon"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18" />
              <line x1="7" y1="2" x2="7" y2="22" />
              <line x1="17" y1="2" x2="17" y2="22" />
              <line x1="2" y1="12" x2="22" y2="12" />
              <line x1="2" y1="7" x2="7" y2="7" />
              <line x1="2" y1="17" x2="7" y2="17" />
              <line x1="17" y1="17" x2="22" y2="17" />
              <line x1="17" y1="7" x2="22" y2="7" />
            </svg>
            <span>{t('workflow.download.adVideo', { name: output.name })}</span>
          </div>
        </div>

        {showMedia && mediaUrl && (
          <div className="video-player-frame">
            <video
              className="video-player-element"
              controls
              controlsList={allowDownload ? undefined : 'nodownload'}
              autoPlay
              playsInline
              src={mediaUrl}
              onError={retryMedia}
            >
              <track kind="captions" />
              {t('workflow.download.cannotPlay')}
            </video>
          </div>
        )}
        {showMedia && mediaFailed && (
          <p role="alert" className="run-step-download-error">
            {t('workflow.download.playFailed')}
          </p>
        )}

        <div className="video-artifact-actions run-step-downloads">
          <button
            type="button"
            className="video-toggle-btn"
            onClick={toggleMedia}
            aria-disabled={outputUrl.isPending}
            aria-busy={outputUrl.isPending}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              {showMedia ? (
                <rect x="6" y="4" width="4" height="16" />
              ) : (
                <polygon points="5 3 19 12 5 21 5 3" />
              )}
            </svg>
            <span>
              {showMedia
                ? hideText
                : outputUrl.isPending
                  ? t('workflow.download.loadingVideo')
                  : watchText}
            </span>
          </button>

          {allowDownload && (
            <button
              type="button"
              className="video-download-btn action-cta"
              onClick={download}
              aria-disabled={outputUrl.isPending}
              aria-busy={outputUrl.isPending}
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              <span>
                {outputUrl.isPending
                  ? t('workflow.download.gettingFile')
                  : (downloadLabel ?? label(output, t))}
              </span>
            </button>
          )}
        </div>

        {!allowDownload && (
          <p className="pv-intermediate-note">
            {previewNote ?? t('workflow.download.intermediateClipNote')}
          </p>
        )}

        {outputUrl.isError && (
          <p role="alert" className="run-step-download-error">
            {t('workflow.download.linkError')}
          </p>
        )}
      </div>
    );
  }

  if (!allowDownload) {
    return null;
  }

  return (
    <div className="run-step-downloads">
      {output.mime?.startsWith('audio/') && (
        <AudioWaveform runId={runId} stepOrder={stepOrder} name={output.name} />
      )}
      <button
        type="button"
        onClick={download}
        aria-disabled={outputUrl.isPending}
        aria-busy={outputUrl.isPending}
      >
        {outputUrl.isPending
          ? t('workflow.download.gettingFile')
          : (downloadLabel ?? label(output, t))}
      </button>
      {outputUrl.isError && (
        <p role="alert" className="run-step-download-error">
          {t('workflow.download.linkError')}
        </p>
      )}
    </div>
  );
}
