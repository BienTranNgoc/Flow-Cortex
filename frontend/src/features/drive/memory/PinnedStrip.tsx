import type { MemoryEntryResponse } from '@flow-cortex/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { MemoryResultCard } from './MemoryResultCard';
import './DriveMemory.css';

const SHOWN_COLLAPSED = 4;

interface PinnedStripProps {
  /** The server's most recent pins, unfiltered. */
  pinned: readonly MemoryEntryResponse[];
  pendingIds: ReadonlySet<string>;
  onOpen: (entry: MemoryEntryResponse) => void;
  onTogglePin: (entry: MemoryEntryResponse) => void;
  onHide: (entry: MemoryEntryResponse) => void;
  onRenew: () => void;
}

/** The pinned results above the list: the first few, the rest behind "Show more". */
export function PinnedStrip({ pinned, pendingIds, onOpen, onTogglePin, onHide, onRenew }: Readonly<PinnedStripProps>) {
  const { t } = useTranslation();
  const listId = useId();
  const [expanded, setExpanded] = useState(false);
  if (pinned.length === 0) return null;

  const hidden = pinned.length - SHOWN_COLLAPSED;
  const shown = expanded ? pinned : pinned.slice(0, SHOWN_COLLAPSED);

  return (
    <section className="drive-memory__pinned" aria-labelledby={`${listId}-heading`}>
      <h2 id={`${listId}-heading`} className="drive-memory__heading">
        {t('driveMemory.pinned.heading')}
      </h2>
      <p className="drive-memory__note">{t('driveMemory.pinned.note')}</p>
      <ul id={listId} className="drive-memory__grid">
        {shown.map((entry) => (
          <li key={entry.id}>
            <MemoryResultCard
              entry={entry}
              pending={pendingIds.has(entry.id)}
              onOpen={onOpen}
              onTogglePin={onTogglePin}
              onHide={onHide}
              onRenew={onRenew}
            />
          </li>
        ))}
      </ul>
      {hidden > 0 && (
        <button
          type="button"
          className="drive-button"
          aria-expanded={expanded}
          aria-controls={listId}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded
            ? t('driveMemory.pinned.showFewer')
            : t('driveMemory.pinned.showMore', { n: formatNumber(hidden, activeLocale()) })}
        </button>
      )}
    </section>
  );
}
