import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { mediaErrorAction } from '../../../lib/signedLinks';
import { DriveFailed, DriveLoading } from './DriveStates';
import './DriveShared.css';

interface SignedMediaProps {
  kind: 'image' | 'video' | 'audio';
  /** The signed link; an item without one shows the unavailable placeholder. */
  url?: string | undefined;
  expiresAt?: string | undefined;
  /** Alt text of an image, accessible name of a player. */
  label: string;
  /** Asks the owner of the link for a fresh one; called once until a link loads. */
  onRenew: () => void;
  /** Shows a loading line until the file loads and a Retry beside the unavailable placeholder. */
  withStatus?: boolean;
  className?: string;
}

/**
 * A file from a signed link. A load error on a link that has expired or is about to means
 * "renew", not "gone": the owner is asked once, and only then does the placeholder show.
 */
export function SignedMedia({
  kind,
  url,
  expiresAt,
  label,
  onRenew,
  withStatus = false,
  className,
}: Readonly<SignedMediaProps>) {
  const { t } = useTranslation();
  // A new attempt remounts the element, so a retry reloads even when the link is unchanged.
  const [attempt, setAttempt] = useState(0);
  const [failedKey, setFailedKey] = useState<string | null>(null);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  // Cleared by a successful load, so a renewed link that fails too cannot loop.
  const renewalAsked = useRef(false);
  const key = `${attempt}|${url}`;

  function handleError() {
    if (url === undefined) return;
    if (mediaErrorAction(renewalAsked.current, expiresAt) === 'renew') {
      renewalAsked.current = true;
      onRenew();
      return;
    }
    setFailedKey(key);
  }

  function handleLoaded() {
    renewalAsked.current = false;
    setLoadedKey(key);
  }

  function retry() {
    renewalAsked.current = false;
    setAttempt((count) => count + 1);
    onRenew();
  }

  if (url === undefined || failedKey === key) {
    return withStatus ? (
      <DriveFailed message={t('drive.media.unavailable')} onRetry={retry} />
    ) : (
      <p className="drive-media drive-media--unavailable">{t('drive.media.unavailable')}</p>
    );
  }

  const classes = `drive-media drive-media--${kind}${className ? ` ${className}` : ''}`;
  const loading = withStatus && loadedKey !== key ? <DriveLoading /> : null;
  if (kind === 'image') {
    return (
      <>
        {loading}
        <img
          key={attempt}
          className={classes}
          src={url}
          alt={label}
          loading="lazy"
          onLoad={handleLoaded}
          onError={handleError}
        />
      </>
    );
  }
  const Player = kind === 'video' ? 'video' : 'audio';
  return (
    <>
      {loading}
      <Player
        key={attempt}
        className={classes}
        src={url}
        aria-label={label}
        controls
        preload="metadata"
        onLoadedMetadata={handleLoaded}
        onError={handleError}
      />
    </>
  );
}
