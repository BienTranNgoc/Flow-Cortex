import type { RunStepOutputResponse } from '@flow-cortex/api-client';
import { useRef, useState } from 'react';
import { openInCurrentTab } from './browser';
import { useOutputUrlMutation } from './useWorkflows';

interface StoredOutputDownloadProps {
  runId: string;
  stepOrder: number;
  output: RunStepOutputResponse;
}

function label(output: RunStepOutputResponse): string {
  if (output.mime === 'audio/wav') {
    return 'Download extracted audio (WAV)';
  }
  return `Download ${output.name}`;
}

export function StoredOutputDownload({
  runId,
  stepOrder,
  output,
}: Readonly<StoredOutputDownloadProps>) {
  const outputUrl = useOutputUrlMutation(runId, stepOrder, output.name);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [showVideo, setShowVideo] = useState(false);
  const [videoRetried, setVideoRetried] = useState(false);
  const [videoFailed, setVideoFailed] = useState(false);

  const isVideo = output.mime?.startsWith('video/') || output.name.endsWith('.mp4');

  // Links are presigned for a limited time, so every download and every opening of the player
  // asks for a new one rather than reusing a link that may have expired since.
  const download = () => {
    if (outputUrl.isPending) {
      return;
    }
    outputUrl.mutate(undefined, {
      onSuccess: ({ url }) => openInCurrentTab(url),
    });
  };

  // Hiding the player starts a new opening, so a link that arrives for an earlier one is dropped
  // instead of reopening the player the member just closed.
  const opening = useRef(0);

  const loadVideo = () => {
    const current = opening.current;
    outputUrl.mutate(undefined, {
      onSuccess: ({ url }) => {
        if (opening.current !== current) {
          return;
        }
        setVideoUrl(url);
        setShowVideo(true);
      },
    });
  };

  const toggleVideo = () => {
    if (showVideo) {
      opening.current += 1;
      setShowVideo(false);
      setVideoUrl(null);
      return;
    }
    if (outputUrl.isPending) {
      return;
    }
    setVideoRetried(false);
    setVideoFailed(false);
    loadVideo();
  };

  // A link that expired while the player was open fails to load; one fresh link recovers it,
  // and a second failure is not a stale link, so it is reported instead of retried.
  const retryVideo = () => {
    if (videoRetried || outputUrl.isPending) {
      setVideoFailed(true);
      return;
    }
    setVideoRetried(true);
    loadVideo();
  };

  if (isVideo) {
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
            <span>Ad video ({output.name})</span>
          </div>
        </div>

        {showVideo && videoUrl && (
          <div className="video-player-frame">
            <video
              className="video-player-element"
              controls
              autoPlay
              playsInline
              src={videoUrl}
              onError={retryVideo}
            >
              Your browser cannot play this video.
            </video>
          </div>
        )}
        {showVideo && videoFailed && (
          <p role="alert" className="run-step-download-error">
            Could not play the video. Download it instead.
          </p>
        )}

        <div className="video-artifact-actions run-step-downloads">
          <button
            type="button"
            className="video-toggle-btn"
            onClick={toggleVideo}
            aria-disabled={outputUrl.isPending}
            aria-busy={outputUrl.isPending}
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="currentColor"
              aria-hidden="true"
            >
              {showVideo ? (
                <rect x="6" y="4" width="4" height="16" />
              ) : (
                <polygon points="5 3 19 12 5 21 5 3" />
              )}
            </svg>
            <span>
              {showVideo
                ? 'Hide video'
                : outputUrl.isPending
                  ? 'Loading the video…'
                  : 'Watch video'}
            </span>
          </button>

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
            <span>{outputUrl.isPending ? 'Getting the file…' : label(output)}</span>
          </button>
        </div>

        {outputUrl.isError && (
          <p role="alert" className="run-step-download-error">
            Could not get download link
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="run-step-downloads">
      <button
        type="button"
        onClick={download}
        aria-disabled={outputUrl.isPending}
        aria-busy={outputUrl.isPending}
      >
        {outputUrl.isPending ? 'Getting the file…' : label(output)}
      </button>
      {outputUrl.isError && (
        <p role="alert" className="run-step-download-error">
          Could not get download link
        </p>
      )}
    </div>
  );
}
