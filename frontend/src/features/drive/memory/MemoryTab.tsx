import type { MemoryEntryResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useReducer, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { problemDetailOf } from '../../../lib/problemMessage';
import {
  PAGE_SIZES,
  SORTS,
  initialMemoryQuery,
  memoryQueryReducer,
  toParams,
  type MemorySort,
} from '../model/memoryQuery';
import { pageAfterShrink } from '../model/pageWindow';
import { ConfirmDialog } from '../shared/ConfirmDialog';
import { DriveFailed, DriveLoading } from '../shared/DriveStates';
import { Pagination } from '../shared/Pagination';
import { PreviewDialog } from '../shared/PreviewDialog';
import { FullTextDialog } from './FullTextDialog';
import { MemoryFilterPanel } from './MemoryFilterPanel';
import { MemoryResultCard } from './MemoryResultCard';
import { PinnedStrip } from './PinnedStrip';
import { useHideMemory, useMemoryQuery, usePinMemory, useUnpinMemory } from './useDriveMemory';
import './DriveMemory.css';

const SEARCH_DEBOUNCE_MS = 300;
// The server matches titles up to this many characters.
const SEARCH_MAX = 200;

/** The member's Memory: search, filters, sort and pages over finished run results, with a pinned strip. */
export function MemoryTab() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const headingId = useId();
  const [query, dispatch] = useReducer(memoryQueryReducer, initialMemoryQuery);
  const [searchText, setSearchText] = useState('');
  const [pendingIds, setPendingIds] = useState<ReadonlySet<string>>(new Set());
  const [announcement, setAnnouncement] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const [hideTarget, setHideTarget] = useState<MemoryEntryResponse | null>(null);
  const [hideError, setHideError] = useState<string | null>(null);
  const [focusResults, setFocusResults] = useState(false);
  const resultsRef = useRef<HTMLElement>(null);

  const built = toParams(query);
  const memory = useMemoryQuery('params' in built ? built.params : null);
  const pin = usePinMemory();
  const unpin = useUnpinMemory();
  const hide = useHideMemory();
  const data = memory.data;

  useEffect(() => {
    const trimmed = searchText.trim();
    if (trimmed === query.q.trim()) return undefined;
    const timer = setTimeout(() => dispatch({ type: 'search', q: trimmed }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchText, query.q]);

  // The card that opened the hide dialog is gone once the result is hidden, so focus lands on the results.
  useEffect(() => {
    if (focusResults) {
      resultsRef.current?.focus();
      setFocusResults(false);
    }
  }, [focusResults]);

  const shrinkTo =
    data && !memory.isPlaceholderData && data.items.length === 0 && data.total > 0
      ? pageAfterShrink(query.page, data.total, query.pageSize)
      : query.page;
  useEffect(() => {
    if (shrinkTo !== query.page) dispatch({ type: 'page', page: shrinkTo });
  }, [shrinkTo, query.page]);

  const filterCount = query.types.length + query.sizes.length + (query.time === 'all' ? 0 : 1);
  const narrowed = query.q.trim() !== '' || filterCount > 0;
  const openEntry =
    openId === null ? null : [...(data?.items ?? []), ...(data?.pinned ?? [])].find((entry) => entry.id === openId) ?? null;

  function renew() {
    void memory.refetch();
  }

  function clearFilters() {
    setSearchText('');
    dispatch({ type: 'clear' });
  }

  async function togglePin(entry: MemoryEntryResponse) {
    setPendingIds((ids) => new Set(ids).add(entry.id));
    try {
      await (entry.pinned ? unpin : pin).mutateAsync(entry.id);
      setAnnouncement(t(entry.pinned ? 'driveMemory.announce.unpinned' : 'driveMemory.announce.pinned', { title: entry.title }));
    } catch (error) {
      setAnnouncement(problemDetailOf(error) ?? t('driveMemory.announce.failed'));
    } finally {
      setPendingIds((ids) => {
        const next = new Set(ids);
        next.delete(entry.id);
        return next;
      });
    }
  }

  async function confirmHide(entry: MemoryEntryResponse) {
    setHideError(null);
    try {
      await hide.mutateAsync(entry.id);
      setHideTarget(null);
      setFocusResults(true);
      setAnnouncement(t('driveMemory.announce.hidden', { title: entry.title }));
    } catch (error) {
      setHideError(problemDetailOf(error) ?? t('driveMemory.announce.failed'));
    }
  }

  function renderResults() {
    if (!data) {
      return memory.isError ? (
        <DriveFailed message={problemDetailOf(memory.error) ?? t('drive.status.failed')} onRetry={renew} />
      ) : (
        <DriveLoading />
      );
    }
    if (shrinkTo !== query.page) return <DriveLoading />;
    if (data.items.length === 0) {
      return (
        <div className="drive-memory__empty">
          <p className="drive-memory__empty-title">
            {narrowed ? t('driveMemory.empty.noMatchTitle') : t('driveMemory.empty.title')}
          </p>
          {narrowed && (
            <>
              <p className="drive-memory__note">{t('driveMemory.empty.noMatchHint')}</p>
              <button type="button" className="drive-button" onClick={clearFilters}>
                {t('driveMemory.empty.clear')}
              </button>
            </>
          )}
        </div>
      );
    }
    return (
      <>
        <ul className="drive-memory__grid" aria-label={t('driveMemory.results.label')}>
          {data.items.map((entry) => (
            <li key={entry.id}>
              <MemoryResultCard
                entry={entry}
                pending={pendingIds.has(entry.id)}
                onOpen={(opened) => setOpenId(opened.id)}
                onTogglePin={(toggled) => void togglePin(toggled)}
                onHide={(target) => {
                  setHideError(null);
                  setHideTarget(target);
                }}
                onRenew={renew}
              />
            </li>
          ))}
        </ul>
        <Pagination
          page={query.page}
          total={data.total}
          size={query.pageSize}
          onPage={(page) => dispatch({ type: 'page', page })}
          sizes={PAGE_SIZES}
          onSize={(pageSize) => dispatch({ type: 'pageSize', pageSize })}
        />
      </>
    );
  }

  return (
    <div className="drive-memory">
      <div className="drive-memory__toolbar" role="search" aria-label={t('driveMemory.toolbar.label')}>
        <label className="drive-memory__search">
          <span className="visually-hidden">{t('driveMemory.toolbar.search')}</span>
          <input
            type="search"
            value={searchText}
            maxLength={SEARCH_MAX}
            placeholder={t('driveMemory.toolbar.search')}
            onChange={(event) => setSearchText(event.target.value)}
          />
        </label>
        <MemoryFilterPanel
          query={query}
          sizeBuckets={data?.sizeBuckets}
          activeCount={filterCount}
          onApply={(filters) => dispatch({ type: 'filters', ...filters })}
        />
        <label className="drive-memory__sort">
          <span>{t('driveMemory.toolbar.sort')}</span>
          <select
            value={query.sort}
            onChange={(event) => dispatch({ type: 'sort', sort: event.target.value as MemorySort })}
          >
            {SORTS.map((sort) => (
              <option key={sort} value={sort}>
                {t(`driveMemory.sort.${sort}`)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {data && (
        <PinnedStrip
          pinned={data.pinned}
          pendingIds={pendingIds}
          onOpen={(opened) => setOpenId(opened.id)}
          onTogglePin={(toggled) => void togglePin(toggled)}
          onHide={(target) => {
            setHideError(null);
            setHideTarget(target);
          }}
          onRenew={renew}
        />
      )}

      <section
        ref={resultsRef}
        tabIndex={-1}
        className="drive-memory__results"
        aria-labelledby={headingId}
        aria-busy={memory.isPlaceholderData || undefined}
      >
        <h2 id={headingId} className="drive-memory__heading">
          {t('driveMemory.results.heading')}
          {data && (
            <span className="drive-memory__count">
              {t('driveMemory.results.total', { count: data.total, n: formatNumber(data.total, locale) })}
            </span>
          )}
        </h2>
        {memory.isPlaceholderData && <output className="drive-memory__busy">{t('driveMemory.results.updating')}</output>}
        {renderResults()}
      </section>

      <FullTextDialog entry={openEntry?.kind === 'text' ? openEntry : null} onClose={() => setOpenId(null)} />
      <PreviewDialog
        open={openEntry !== null && openEntry.kind !== 'text'}
        title={openEntry?.title ?? ''}
        kind={openEntry && openEntry.kind !== 'text' ? openEntry.kind : 'image'}
        url={openEntry?.url}
        urlExpiresAt={openEntry?.urlExpiresAt}
        onRenew={renew}
        onClose={() => setOpenId(null)}
      />
      <ConfirmDialog
        open={hideTarget !== null}
        title={t('driveMemory.hide.title')}
        message={t('driveMemory.hide.message', { title: hideTarget?.title ?? '' })}
        confirmLabel={t('driveMemory.hide.confirm')}
        busyLabel={t('driveMemory.hide.busy')}
        error={hideError}
        busy={hide.isPending}
        onCancel={() => setHideTarget(null)}
        onConfirm={() => {
          if (hideTarget) void confirmHide(hideTarget);
        }}
      />
      <div role="status" className="visually-hidden">
        {announcement}
      </div>
    </div>
  );
}
