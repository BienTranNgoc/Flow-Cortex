import type { SizeBuckets } from '@flow-cortex/api-client';
import { Funnel } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import {
  SIZES,
  TIMES,
  TYPES,
  toParams,
  type MemoryQuery,
  type MemorySize,
  type MemoryTime,
  type MemoryType,
} from '../model/memoryQuery';
import './DriveMemory.css';

export interface MemoryFilters {
  types: MemoryType[];
  time: MemoryTime;
  from: string;
  to: string;
  sizes: MemorySize[];
}

interface MemoryFilterPanelProps {
  query: MemoryQuery;
  /** Entries per size bucket, when the list has loaded. */
  sizeBuckets?: SizeBuckets | undefined;
  /** How many filters the list is under now, shown on the button. */
  activeCount: number;
  onApply: (filters: MemoryFilters) => void;
}

function filtersOf(query: MemoryQuery): MemoryFilters {
  return { types: query.types, time: query.time, from: query.from, to: query.to, sizes: query.sizes };
}

function toggled<T>(values: readonly T[], value: T): T[] {
  return values.includes(value) ? values.filter((entry) => entry !== value) : [...values, value];
}

/**
 * The Filters button and its panel (a popover from 768px, a full-width sheet below). Choices wait
 * in a draft until Apply, and a custom range that cannot be sent is refused inside the panel.
 */
export function MemoryFilterPanel({ query, sizeBuckets, activeCount, onApply }: Readonly<MemoryFilterPanelProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const panelId = useId();
  const timeName = useId();
  const rangeErrorId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<MemoryFilters>(() => filtersOf(query));
  const [rangeError, setRangeError] = useState<'rangeNeedsDate' | 'rangeReversed' | null>(null);

  // A tap or click outside closes the panel and leaves focus where the member put it.
  useEffect(() => {
    if (!open) return undefined;
    function onPointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false);
    }
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  function close() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  function toggle() {
    if (open) {
      close();
      return;
    }
    setDraft(filtersOf(query));
    setRangeError(null);
    setOpen(true);
    requestAnimationFrame(() => panelRef.current?.querySelector<HTMLElement>('input')?.focus());
  }

  function change(next: Partial<MemoryFilters>) {
    setDraft((current) => ({ ...current, ...next }));
    setRangeError(null);
  }

  function apply() {
    if ('error' in toParams({ ...query, ...draft })) {
      setRangeError(!draft.from && !draft.to ? 'rangeNeedsDate' : 'rangeReversed');
      return;
    }
    onApply(draft);
    close();
  }

  function onKeyDown(event: KeyboardEvent) {
    if (open && event.key === 'Escape') {
      event.stopPropagation();
      close();
    }
  }

  const count = (size: MemorySize) => (sizeBuckets ? ` (${formatNumber(sizeBuckets[size], locale)})` : '');

  return (
    <div ref={rootRef} className="drive-memory__filters" onKeyDown={onKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        className="drive-button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={toggle}
      >
        <Funnel aria-hidden />
        {activeCount > 0
          ? t('driveMemory.toolbar.filtersActive', { n: formatNumber(activeCount, locale) })
          : t('driveMemory.toolbar.filters')}
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="group"
          aria-label={t('driveMemory.filters.label')}
          className="drive-memory__panel"
        >
          <fieldset className="drive-memory__fieldset">
            <legend>{t('driveMemory.filters.types')}</legend>
            {TYPES.map((type) => (
              <label key={type} className="drive-memory__choice">
                <input
                  type="checkbox"
                  checked={draft.types.includes(type)}
                  onChange={() => change({ types: toggled(draft.types, type) })}
                />
                {t(`driveMemory.types.${type}`)}
              </label>
            ))}
          </fieldset>
          <fieldset className="drive-memory__fieldset">
            <legend>{t('driveMemory.filters.time')}</legend>
            {TIMES.map((time) => (
              <label key={time} className="drive-memory__choice">
                <input
                  type="radio"
                  name={timeName}
                  checked={draft.time === time}
                  onChange={() => change({ time })}
                />
                {t(`driveMemory.times.${time}`)}
              </label>
            ))}
            {draft.time === 'custom' && (
              <div className="drive-memory__dates">
                <label className="drive-memory__date">
                  <span>{t('driveMemory.filters.from')}</span>
                  <input
                    type="date"
                    value={draft.from}
                    aria-invalid={rangeError !== null || undefined}
                    aria-describedby={rangeError ? rangeErrorId : undefined}
                    onChange={(event) => change({ from: event.target.value })}
                  />
                </label>
                <label className="drive-memory__date">
                  <span>{t('driveMemory.filters.to')}</span>
                  <input
                    type="date"
                    value={draft.to}
                    aria-invalid={rangeError !== null || undefined}
                    aria-describedby={rangeError ? rangeErrorId : undefined}
                    onChange={(event) => change({ to: event.target.value })}
                  />
                </label>
              </div>
            )}
          </fieldset>
          <fieldset className="drive-memory__fieldset">
            <legend>{t('driveMemory.filters.sizes')}</legend>
            {SIZES.map((size) => (
              <label key={size} className="drive-memory__choice">
                <input
                  type="checkbox"
                  checked={draft.sizes.includes(size)}
                  onChange={() => change({ sizes: toggled(draft.sizes, size) })}
                />
                {t(`driveMemory.sizes.${size}`)}
                {count(size)}
              </label>
            ))}
          </fieldset>
          {rangeError && (
            <p id={rangeErrorId} role="alert" className="drive-memory__error">
              {t(`driveMemory.filters.${rangeError}`)}
            </p>
          )}
          <div className="drive-memory__panel-actions">
            <button
              type="button"
              className="drive-button"
              onClick={() => change({ types: [], time: 'all', from: '', to: '', sizes: [] })}
            >
              {t('driveMemory.filters.reset')}
            </button>
            <button type="button" className="drive-button" onClick={close}>
              {t('driveMemory.filters.close')}
            </button>
            <button type="button" className="drive-button drive-button--primary" onClick={apply}>
              {t('driveMemory.filters.apply')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
