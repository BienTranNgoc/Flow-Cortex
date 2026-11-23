import { EyeSlash, Image, PushPin, SpeakerHigh, TextT, VideoCamera, type Icon } from '@phosphor-icons/react';
import type { MemoryEntryResponse, MemoryEntryResponseKind } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { formatBytes } from '../model/formatBytes';
import { SignedMedia } from '../shared/SignedMedia';
import './DriveMemory.css';

const KIND_ICONS: Record<MemoryEntryResponseKind, Icon> = {
  text: TextT,
  image: Image,
  video: VideoCamera,
  audio: SpeakerHigh,
};

const RESULT_AT_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

interface MemoryResultCardProps {
  entry: MemoryEntryResponse;
  /** An action on this result is running: its own buttons hold. */
  pending: boolean;
  onOpen: (entry: MemoryEntryResponse) => void;
  onTogglePin: (entry: MemoryEntryResponse) => void;
  onHide: (entry: MemoryEntryResponse) => void;
  onRenew: () => void;
}

/** One Memory result: what it is, a look at it, and the open, pin and hide actions. */
export function MemoryResultCard({
  entry,
  pending,
  onOpen,
  onTogglePin,
  onHide,
  onRenew,
}: Readonly<MemoryResultCardProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const KindIcon = KIND_ICONS[entry.kind];

  return (
    <article className="drive-memory-card" aria-busy={pending || undefined}>
      <header className="drive-memory-card__head">
        <KindIcon aria-hidden />
        <h3 className="drive-memory-card__title">{entry.title}</h3>
      </header>
      {entry.kind === 'text' ? (
        entry.previewText && <p className="drive-memory-card__preview">{entry.previewText}</p>
      ) : (
        <SignedMedia
          kind={entry.kind}
          url={entry.url}
          expiresAt={entry.urlExpiresAt}
          label={entry.title}
          onRenew={onRenew}
          className="drive-memory-card__media"
        />
      )}
      <p className="drive-memory-card__meta">
        <span>{t(`driveMemory.types.${entry.kind}`)}</span>
        <span>{formatBytes(entry.sizeBytes, locale)}</span>
        <span>{formatDateTime(entry.resultAt, locale, RESULT_AT_FORMAT)}</span>
      </p>
      <div className="drive-memory-card__actions">
        <button
          type="button"
          className="drive-button"
          aria-label={t('driveMemory.card.openLabel', { title: entry.title })}
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) onOpen(entry);
          }}
        >
          {t('driveMemory.card.open')}
        </button>
        <button
          type="button"
          className="drive-button drive-memory-card__pin"
          aria-label={t('driveMemory.card.pinLabel', { title: entry.title })}
          aria-pressed={entry.pinned}
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) onTogglePin(entry);
          }}
        >
          <PushPin aria-hidden weight={entry.pinned ? 'fill' : 'regular'} />
          {t('driveMemory.card.pin')}
        </button>
        <button
          type="button"
          className="drive-button"
          aria-label={t('driveMemory.card.hideLabel', { title: entry.title })}
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) onHide(entry);
          }}
        >
          <EyeSlash aria-hidden />
          {t('driveMemory.card.hide')}
        </button>
      </div>
    </article>
  );
}
