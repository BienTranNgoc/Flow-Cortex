import { ApiError } from '@flow-cortex/api-client';
import type { RunHistoryRowResponse } from '@flow-cortex/api-client';
import { DotsThreeVertical, Star } from '@phosphor-icons/react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { formatCredits } from '../credits/formatCredits';
import { DeleteRunDialog } from './DeleteRunDialog';
import { downloadRunsCsv } from './downloadRunsCsv';
import { RenameRunDialog } from './RenameRunDialog';
import { formatSpan, formatStart, runName } from './runDisplay';
import {
  canDeleteRun,
  parse,
  serialize,
  type RunHistoryFilters,
  type RunStatus,
} from './runHistoryRules';
import {
  RUN_HISTORY_PAGE_SIZE,
  useDeleteRunMutation,
  useRunHistoryQuery,
  useRunWorkflowsQuery,
  useUpdateRunMutation,
} from './useRunHistory';
import { UsageCard } from './UsageCard';
import './RunHistoryPage.css';

/** The server's export cap; a bigger result is cut to this many rows. */
const EXPORT_CAP = 1000;

const STATUS_OPTIONS: readonly RunStatus[] = [
  'running',
  'waiting_approval',
  'completed',
  'error',
  'cancelling',
  'cancelled',
];

const STATUS_TONE: Record<RunStatus, string> = {
  running: 'running',
  waiting_approval: 'attention',
  completed: 'done',
  error: 'failed',
  cancelling: 'attention',
  cancelled: 'muted',
};

function hasFilters(filters: RunHistoryFilters): boolean {
  return Boolean(
    filters.status || filters.workflowId || filters.from || filters.to || filters.favorite,
  );
}

interface RowMenuProps {
  readonly run: RunHistoryRowResponse;
  readonly onRename: () => void;
  readonly onDelete: () => void;
}

/** The row's actions: a disclosure menu that closes on Escape or an outside click and returns focus to its button. */
function RowMenu({ run, onRename, onDelete }: Readonly<RowMenuProps>) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const name = runName(run);

  useEffect(() => {
    if (!open) return undefined;
    rootRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const onPointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointer);
    return () => document.removeEventListener('pointerdown', onPointer);
  }, [open]);

  const close = () => {
    setOpen(false);
    buttonRef.current?.focus();
  };

  const move = (step: number) => {
    const items = Array.from(
      rootRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [],
    );
    const at = items.indexOf(document.activeElement as HTMLElement);
    items[(at + step + items.length) % items.length]?.focus();
  };

  return (
    <div
      ref={rootRef}
      className="run-menu"
      onKeyDown={(event) => {
        if (!open) return;
        if (event.key === 'Escape') {
          event.stopPropagation();
          close();
        } else if (event.key === 'ArrowDown') {
          event.preventDefault();
          move(1);
        } else if (event.key === 'ArrowUp') {
          event.preventDefault();
          move(-1);
        }
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className="run-menu__button"
        aria-label={t('runHistory.row.actions', { name })}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <DotsThreeVertical size={20} weight="bold" aria-hidden="true" />
      </button>
      {open && (
        <div id={menuId} role="menu" className="run-menu__list">
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onRename();
            }}
          >
            {t('runHistory.row.rename')}
          </button>
          {canDeleteRun(run.status) && (
            <button
              type="button"
              role="menuitem"
              className="run-menu__danger"
              onClick={() => {
                setOpen(false);
                onDelete();
              }}
            >
              {t('runHistory.row.delete')}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function problemKey(error: unknown, failedKey: string, stillActiveKey?: string): string {
  return stillActiveKey && error instanceof ApiError && error.status === 409
    ? stillActiveKey
    : failedKey;
}

/** `/runs` — the member's run history: filters kept in the address, usage card, star, rename, delete and CSV export. */
export function RunHistoryPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [search, setSearch] = useSearchParams();
  const filters = useMemo(() => parse(search), [search]);
  const listQuery = useRunHistoryQuery(filters);
  const workflowsQuery = useRunWorkflowsQuery();
  const updateRun = useUpdateRunMutation();
  const deleteRun = useDeleteRunMutation();

  const [renaming, setRenaming] = useState<RunHistoryRowResponse | null>(null);
  const [deleting, setDeleting] = useState<RunHistoryRowResponse | null>(null);
  const [starFailed, setStarFailed] = useState(false);
  const [exportState, setExportState] = useState<
    | { kind: 'idle' }
    | { kind: 'busy' }
    | { kind: 'failed' }
    | { kind: 'done'; total: number | null }
  >({ kind: 'idle' });

  const change = (next: Partial<RunHistoryFilters>, replace = false) => {
    setSearch(serialize({ ...filters, page: 1, ...next }), { replace });
  };

  const page = listQuery.data;
  const lastPage = page ? Math.max(1, Math.ceil(page.total / RUN_HISTORY_PAGE_SIZE)) : 1;

  // A delete can empty the last page: land on the new last one rather than on "nothing".
  useEffect(() => {
    if (page && page.items.length === 0 && page.total > 0 && filters.page > lastPage) {
      setSearch(serialize({ ...filters, page: lastPage }), { replace: true });
    }
  }, [page, lastPage, filters, setSearch]);

  const workflows = workflowsQuery.data ?? [];
  const selectedMissing =
    filters.workflowId !== undefined &&
    workflowsQuery.isSuccess &&
    !workflows.some((workflow) => workflow.id === filters.workflowId);

  const startExport = async () => {
    setExportState({ kind: 'busy' });
    try {
      const { total } = await downloadRunsCsv(filters);
      setExportState({ kind: 'done', total });
    } catch {
      setExportState({ kind: 'failed' });
    }
  };

  const toggleStar = (run: RunHistoryRowResponse) => {
    setStarFailed(false);
    updateRun.mutate(
      { id: run.id, patch: { favorite: !run.favorite } },
      { onError: () => setStarFailed(true) },
    );
  };

  const renameError =
    updateRun.isError && renaming
      ? t(problemKey(updateRun.error, 'runHistory.rename.failed'))
      : null;
  const deleteError =
    deleteRun.isError && deleting
      ? t(problemKey(deleteRun.error, 'runHistory.delete.failed', 'runHistory.delete.stillActive'))
      : null;

  const exportNote =
    exportState.kind === 'done' && exportState.total !== null && exportState.total > EXPORT_CAP
      ? t('runHistory.export.capped', {
          cap: formatNumber(EXPORT_CAP, locale),
          total: formatNumber(exportState.total, locale),
        })
      : null;

  return (
    <div className="run-history">
      <PageHeading
        title={t('runHistory.page.title')}
        lede={t('runHistory.page.lede')}
        actions={
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            aria-disabled={exportState.kind === 'busy'}
            aria-busy={exportState.kind === 'busy'}
            onClick={() => {
              if (exportState.kind !== 'busy') void startExport();
            }}
          >
            {exportState.kind === 'busy'
              ? t('runHistory.export.busy')
              : t('runHistory.export.button')}
          </button>
        }
      />

      {exportState.kind === 'failed' && (
        <p className="run-history__notice run-history__notice--error" role="alert">
          {t('runHistory.export.failed')}
        </p>
      )}
      {exportNote && (
        <p className="run-history__notice" role="status">
          {exportNote}
        </p>
      )}
      {starFailed && (
        <p className="run-history__notice run-history__notice--error" role="alert">
          {t('runHistory.row.starFailed')}
        </p>
      )}

      <div className="run-history__body">
        <div className="run-history__main">
          <form
            className="run-history__filters"
            aria-label={t('runHistory.filters.legend')}
            onSubmit={(event) => event.preventDefault()}
          >
            <label className="run-history__field">
              <span>{t('runHistory.filters.status')}</span>
              <select
                value={filters.status ?? ''}
                onChange={(event) =>
                  change({ status: (event.target.value || undefined) as RunStatus | undefined })
                }
              >
                <option value="">{t('runHistory.filters.statusAll')}</option>
                {STATUS_OPTIONS.map((status) => (
                  <option key={status} value={status}>
                    {t(`runHistory.status.${status}`)}
                  </option>
                ))}
              </select>
            </label>
            <label className="run-history__field">
              <span>{t('runHistory.filters.workflow')}</span>
              <select
                value={filters.workflowId ?? ''}
                onChange={(event) => change({ workflowId: event.target.value || undefined })}
              >
                <option value="">{t('runHistory.filters.workflowAll')}</option>
                {selectedMissing && (
                  <option value={filters.workflowId}>{filters.workflowId}</option>
                )}
                {workflows.map((workflow) => (
                  <option key={workflow.id} value={workflow.id}>
                    {workflow.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="run-history__field">
              <span>{t('runHistory.filters.from')}</span>
              <input
                type="date"
                value={filters.from ?? ''}
                onChange={(event) => change({ from: event.target.value || undefined })}
              />
            </label>
            <label className="run-history__field">
              <span>{t('runHistory.filters.to')}</span>
              <input
                type="date"
                value={filters.to ?? ''}
                min={filters.from}
                onChange={(event) => change({ to: event.target.value || undefined })}
              />
            </label>
            <button
              type="button"
              className="run-history__toggle"
              aria-pressed={filters.favorite}
              onClick={() => change({ favorite: !filters.favorite })}
            >
              <Star size={16} weight={filters.favorite ? 'fill' : 'regular'} aria-hidden="true" />
              {t('runHistory.filters.favorites')}
            </button>
          </form>

          <section
            className="run-history__list-section"
            aria-label={t('runHistory.page.listLabel')}
            aria-busy={listQuery.isFetching}
          >
            {listQuery.isError && !page ? (
              <StatusBlock
                kind="error"
                message={t('runHistory.page.loadError')}
                action={{
                  label: t('runHistory.page.retry'),
                  onClick: () => void listQuery.refetch(),
                }}
              />
            ) : !page ? (
              <Skeleton shape="row" count={5} />
            ) : page.items.length === 0 ? (
              hasFilters(filters) ? (
                <StatusBlock
                  kind="empty"
                  message={t('runHistory.page.emptyNoMatch')}
                  action={{
                    label: t('runHistory.page.emptyNoMatchAction'),
                    onClick: () => setSearch(new URLSearchParams()),
                  }}
                />
              ) : (
                <StatusBlock
                  kind="empty"
                  message={t('runHistory.page.emptyNone')}
                  action={{ label: t('runHistory.page.emptyNoneAction'), to: '/workflows' }}
                />
              )
            ) : (
              <ul className="run-history__list">
                {page.items.map((run) => {
                  const name = runName(run);
                  return (
                    <li key={run.id} className="run-row">
                      <div className="run-row__main">
                        <Link
                          to={`/runs/${run.id}`}
                          className="run-row__name"
                          aria-label={t('runHistory.row.open', { name })}
                        >
                          {name}
                        </Link>
                        {run.title?.trim() && (
                          <span className="run-row__workflow">{run.workflowName}</span>
                        )}
                      </div>
                      <span className={`run-pill run-pill--${STATUS_TONE[run.status]}`}>
                        {t(`runHistory.status.${run.status}`)}
                      </span>
                      <div className="run-row__meta">
                        <span>
                          {t('runHistory.row.started', { time: formatStart(run.startedAt) })}
                        </span>
                        {run.durationSeconds !== undefined && (
                          <span>
                            {t('runHistory.row.duration', {
                              duration: formatSpan(run.durationSeconds, t),
                            })}
                          </span>
                        )}
                        {run.creditsCharged !== undefined && (
                          <span>
                            {t('runHistory.row.credits', {
                              amount: formatCredits(run.creditsCharged, locale),
                            })}
                          </span>
                        )}
                      </div>
                      <div className="run-row__actions">
                        <button
                          type="button"
                          className="run-row__star"
                          aria-pressed={run.favorite}
                          aria-label={t(
                            run.favorite ? 'runHistory.row.unstar' : 'runHistory.row.star',
                            { name },
                          )}
                          onClick={() => toggleStar(run)}
                        >
                          <Star
                            size={20}
                            weight={run.favorite ? 'fill' : 'regular'}
                            aria-hidden="true"
                          />
                        </button>
                        <RowMenu
                          run={run}
                          onRename={() => {
                            updateRun.reset();
                            setRenaming(run);
                          }}
                          onDelete={() => {
                            deleteRun.reset();
                            setDeleting(run);
                          }}
                        />
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          {page && page.total > 0 && (
            <nav className="run-history__pager" aria-label={t('runHistory.pager.label')}>
              <span className="run-history__total">
                {t('runHistory.page.total', { n: formatNumber(page.total, locale) })}
              </span>
              <button
                type="button"
                disabled={filters.page <= 1}
                onClick={() => setSearch(serialize({ ...filters, page: filters.page - 1 }))}
              >
                {t('runHistory.pager.previous')}
              </button>
              <span aria-live="polite">
                {t('runHistory.pager.position', {
                  page: formatNumber(Math.min(filters.page, lastPage), locale),
                  pages: formatNumber(lastPage, locale),
                })}
              </span>
              <button
                type="button"
                disabled={filters.page >= lastPage}
                onClick={() => setSearch(serialize({ ...filters, page: filters.page + 1 }))}
              >
                {t('runHistory.pager.next')}
              </button>
            </nav>
          )}
        </div>

        <UsageCard />
      </div>

      <RenameRunDialog
        open={renaming !== null}
        currentTitle={renaming?.title ?? ''}
        saving={updateRun.isPending}
        error={renameError}
        onCancel={() => setRenaming(null)}
        onSave={(title) => {
          if (!renaming) return;
          updateRun.mutate(
            { id: renaming.id, patch: { title } },
            { onSuccess: () => setRenaming(null) },
          );
        }}
      />
      <DeleteRunDialog
        open={deleting !== null}
        runName={deleting ? runName(deleting) : ''}
        deleting={deleteRun.isPending}
        error={deleteError}
        onKeep={() => setDeleting(null)}
        onDelete={() => {
          if (!deleting) return;
          deleteRun.mutate(deleting.id, { onSuccess: () => setDeleting(null) });
        }}
      />
    </div>
  );
}
