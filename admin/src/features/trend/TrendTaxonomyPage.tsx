import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { TaxonomyDeleteDialog } from './TaxonomyDeleteDialog';
import { TaxonomyEntryDialog } from './TaxonomyEntryDialog';
import { describeFailure, templateCountOf, trendProblemCode, type TrendFailure } from './trendProblem';
import {
  NAME_LIMIT,
  useCreateCategoryMutation,
  useCreateTagMutation,
  useDeleteCategoryMutation,
  useDeleteTagMutation,
  useReorderCategoriesMutation,
  useReorderTagsMutation,
  useTaxonomyQuery,
  useUpdateCategoryMutation,
  useUpdateTagMutation,
  type NamePair,
  type TaxonomyKind,
} from './useTrendTaxonomy';
import './trend.css';

type TabId = 'categories' | 'tags';
const TABS: readonly TabId[] = ['categories', 'tags'];
const KIND_OF: Readonly<Record<TabId, TaxonomyKind>> = { categories: 'category', tags: 'tag' };

/** A category (identified by its key) or a tag (by its id), as the table lists it. */
interface Row {
  id: string;
  key?: string;
  nameVi: string;
  nameEn: string;
  visible: boolean;
  usageCount: number;
}

type DialogState = { mode: 'add'; kind: TaxonomyKind } | { mode: 'edit'; kind: TaxonomyKind; row: Row };

interface InUseNotice {
  row: Row;
  count: number;
}

type AnnounceKey = 'created' | 'updated' | 'movedUp' | 'movedDown' | 'deleted' | 'hidden' | 'shown';

/** Kept untranslated so the live region follows a later locale switch. */
interface Announcement {
  key: AnnounceKey;
  names: NamePair;
  position?: number;
}

export function TrendTaxonomyPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  usePageTitle(t('trend.title'));
  const me = useMeQuery().data;
  const canManage = can(me, 'content.trend_template.manage');
  const query = useTaxonomyQuery();
  const createCategory = useCreateCategoryMutation();
  const updateCategory = useUpdateCategoryMutation();
  const reorderCategories = useReorderCategoriesMutation();
  const deleteCategory = useDeleteCategoryMutation();
  const createTag = useCreateTagMutation();
  const updateTag = useUpdateTagMutation();
  const reorderTags = useReorderTagsMutation();
  const deleteTag = useDeleteTagMutation();

  const [active, setActive] = useState<TabId>('categories');
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [confirming, setConfirming] = useState<{ kind: TaxonomyKind; row: Row } | null>(null);
  const [inUse, setInUse] = useState<InUseNotice | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState<Announcement | null>(null);
  const pendingFocus = useRef<string | null>(null);
  const tabRefs = useRef<Partial<Record<TabId, HTMLButtonElement | null>>>({});
  const baseId = useId();

  const busy = [
    createCategory,
    updateCategory,
    reorderCategories,
    deleteCategory,
    createTag,
    updateTag,
    reorderTags,
    deleteTag,
  ].some((mutation) => mutation.isPending);

  const rowsByTab: Record<TabId, Row[]> = {
    categories: [...(query.data?.categories ?? [])]
      .sort((a, b) => a.order - b.order)
      .map((category) => ({ ...category, id: category.key })),
    tags: [...(query.data?.tags ?? [])].sort((a, b) => a.order - b.order),
  };
  const rows = rowsByTab[active];
  const kind = KIND_OF[active];
  const nameOf = (row: NamePair) => (locale === 'vi' ? row.nameVi : row.nameEn);
  const addId = (tab: TabId) => `${baseId}-${tab}-add`;
  const rowControlId = (row: Row, control: 'up' | 'down' | 'edit' | 'delete' | 'visibility') =>
    `${baseId}-${active}-${row.id}-${control}`;

  // A moved or deleted row is re-inserted or removed, which drops focus from the button that was used.
  useEffect(() => {
    if (pendingFocus.current && document.getElementById(pendingFocus.current)) {
      document.getElementById(pendingFocus.current)?.focus();
      pendingFocus.current = null;
    }
  });

  const started = () => {
    setProblem(null);
    setInUse(null);
    setAnnouncement(null);
  };
  const problemText = (error: unknown) =>
    describeFailure(error, t, NAME_LIMIT[kind]).message ?? t('trend.common.saveFailed');
  const failed = (error: unknown) => setProblem(problemText(error));

  const closeDialog = (focusId: string) => {
    pendingFocus.current = focusId;
    setDialog(null);
    setConfirming(null);
  };

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
    let target: number | undefined;
    if (event.key === 'Home') target = 0;
    else if (event.key === 'End') target = TABS.length - 1;
    else if (step !== undefined) target = (index + step + TABS.length) % TABS.length;
    if (target === undefined) return;
    event.preventDefault();
    const next = TABS[target]!;
    setActive(next);
    tabRefs.current[next]?.focus();
  };

  const move = (index: number, offset: -1 | 1) => {
    const row = rows[index];
    if (busy || !row) return;
    const ids = rows.map((item) => item.id);
    ids.splice(index, 1);
    ids.splice(index + offset, 0, row.id);
    started();
    const reorder = kind === 'category' ? reorderCategories : reorderTags;
    reorder.mutate(ids, {
      onSuccess: () => {
        pendingFocus.current = rowControlId(row, offset < 0 ? 'up' : 'down');
        setAnnouncement({ key: offset < 0 ? 'movedUp' : 'movedDown', names: row, position: index + offset + 1 });
      },
      // The write itself refetches the lists; a stale move is reported and never retried.
      onError: failed,
    });
  };

  const setVisible = (row: Row, visible: boolean) => {
    if (busy) return;
    started();
    const onSuccess = () => {
      pendingFocus.current = rowControlId(row, 'visibility');
      setAnnouncement({ key: visible ? 'shown' : 'hidden', names: row });
    };
    if (kind === 'category') {
      updateCategory.mutate({ key: row.id, changes: { visible } }, { onSuccess, onError: failed });
    } else {
      updateTag.mutate({ id: row.id, changes: { visible } }, { onSuccess, onError: failed });
    }
  };

  const submitEntry = async (state: DialogState, names: NamePair): Promise<TrendFailure | null> => {
    started();
    const stateKind = state.kind;
    try {
      if (state.mode === 'add') {
        await (stateKind === 'category' ? createCategory : createTag).mutateAsync(names);
        setAnnouncement({ key: 'created', names });
        closeDialog(addId(stateKind === 'category' ? 'categories' : 'tags'));
      } else if (stateKind === 'category') {
        await updateCategory.mutateAsync({ key: state.row.id, changes: names });
        setAnnouncement({ key: 'updated', names });
        closeDialog(rowControlId(state.row, 'edit'));
      } else {
        await updateTag.mutateAsync({ id: state.row.id, changes: names });
        setAnnouncement({ key: 'updated', names });
        closeDialog(rowControlId(state.row, 'edit'));
      }
      return null;
    } catch (error) {
      const failure = describeFailure(error, t, NAME_LIMIT[state.kind]);
      const code = failure.code;
      if (code === 'taxonomy-full' || code === 'category-not-found' || code === 'tag-not-found') {
        // Nothing left to correct in the dialog: close it and report on the page, where the reloaded list shows.
        setProblem(failure.message ?? null);
        closeDialog(addId(state.kind === 'category' ? 'categories' : 'tags'));
        return null;
      }
      return failure;
    }
  };

  const confirmDelete = () => {
    if (!confirming || busy) return;
    const { row, kind: deleting } = confirming;
    const index = rows.findIndex((item) => item.id === row.id);
    const neighbour = rows[index + 1] ?? rows[index - 1];
    started();
    const remove = deleting === 'category' ? deleteCategory : deleteTag;
    remove.mutate(row.id, {
      onSuccess: () => {
        closeDialog(neighbour ? rowControlId(neighbour, 'delete') : addId(active));
        setAnnouncement({ key: 'deleted', names: row });
      },
      onError: (error) => {
        closeDialog(rowControlId(row, 'delete'));
        if (trendProblemCode(error) === 'category-in-use') {
          setInUse({ row, count: templateCountOf(error) });
        } else {
          failed(error);
        }
      },
    });
  };

  const hideInstead = (notice: InUseNotice) => {
    if (busy) return;
    started();
    updateCategory.mutate(
      { key: notice.row.id, changes: { visible: false } },
      {
        onSuccess: () => {
          pendingFocus.current = rowControlId(notice.row, 'visibility');
          setAnnouncement({ key: 'hidden', names: notice.row });
        },
        onError: failed,
      },
    );
  };

  const deleteBody = (row: Row) => {
    if (kind === 'category') return t('trend.delete.categoryBody');
    return row.usageCount > 0
      ? t('trend.delete.tagBody', { count: row.usageCount })
      : t('trend.delete.tagBodyUnused');
  };

  return (
    <div className="trend-page">
      <PageHeading
        eyebrow={t('trend.eyebrow')}
        title={t('trend.title')}
        description={t('trend.description')}
      />
      {!canManage && <p className="trend-hint">{t('trend.readOnlyHint')}</p>}

      <div className="trend-tabs" role="tablist" aria-label={t('trend.tabs.label')}>
        {TABS.map((tab, index) => (
          <button
            key={tab}
            ref={(node) => {
              tabRefs.current[tab] = node;
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-${tab}`}
            aria-selected={active === tab}
            aria-controls={active === tab ? `${baseId}-panel-${tab}` : undefined}
            tabIndex={active === tab ? 0 : -1}
            className="trend-tab-button"
            onClick={() => setActive(tab)}
            onKeyDown={(event) => onTabKeyDown(event, index)}
          >
            {t(`trend.tabs.${tab}`)}
          </button>
        ))}
      </div>

      <div
        role="tabpanel"
        id={`${baseId}-panel-${active}`}
        aria-labelledby={`${baseId}-tab-${active}`}
        className="trend-panel"
      >
        {canManage && (
          <div className="trend-toolbar">
            <button
              id={addId(active)}
              type="button"
              className="cortex-admin-btn cortex-admin-btn--primary"
              aria-disabled={busy}
              onClick={() => !busy && setDialog({ mode: 'add', kind })}
            >
              {t(`trend.${active}.add`)}
            </button>
          </div>
        )}

        {problem && (
          <p className="cortex-admin-form-error" role="alert">
            {problem}
          </p>
        )}
        {inUse && (
          <div className="trend-notice" role="alert">
            <p>
              {t('trend.inUse.message', { name: nameOf(inUse.row), count: inUse.count })}
            </p>
            <div className="trend-notice__actions">
              {canManage && inUse.row.visible && (
                <button
                  type="button"
                  className="cortex-admin-btn cortex-admin-btn--primary"
                  aria-disabled={busy}
                  onClick={() => hideInstead(inUse)}
                >
                  {t('trend.inUse.hideInstead')}
                </button>
              )}
              <button type="button" className="cortex-admin-btn" onClick={() => setInUse(null)}>
                {t('trend.inUse.dismiss')}
              </button>
            </div>
          </div>
        )}
        <p role="status" className="trend-announcement">
          {announcement &&
            t(`trend.announce.${announcement.key}`, {
              name: nameOf(announcement.names),
              position: announcement.position,
            })}
        </p>

        {query.isPending && <output className="cortex-admin-loading">{t('trend.common.loading')}</output>}
        {query.isError && !query.data && (
          <div className="cortex-admin-error">
            <p>{t('trend.common.loadFailed')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('trend.common.retry')}
            </button>
          </div>
        )}
        {query.isError && query.data && <p className="trend-hint">{t('trend.common.refreshFailed')}</p>}
        {query.data && rows.length === 0 && (
          <div className="cortex-admin-empty">
            <p>
              <strong>{t(`trend.${active}.emptyTitle`)}</strong>
            </p>
            <p>{t(canManage ? `trend.${active}.emptyWritable` : `trend.${active}.emptyReadOnly`)}</p>
          </div>
        )}
        {rows.length > 0 && (
          <TableRegion label={t(`trend.${active}.tableLabel`)} busy={query.isFetching}>
            <table className="cortex-admin-table trend-table">
              <thead>
                <tr>
                  <th scope="col">{t('trend.columns.order')}</th>
                  <th scope="col">{t('trend.columns.nameVi')}</th>
                  <th scope="col">{t('trend.columns.nameEn')}</th>
                  <th scope="col">{t('trend.columns.usage')}</th>
                  <th scope="col">{t('trend.columns.visibility')}</th>
                  {canManage && <th scope="col">{t('trend.columns.actions')}</th>}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr key={row.id}>
                    <td data-label={t('trend.columns.order')}>{index + 1}</td>
                    <td data-label={t('trend.columns.nameVi')}>
                      <strong>{row.nameVi}</strong>
                    </td>
                    <td data-label={t('trend.columns.nameEn')}>{row.nameEn}</td>
                    <td data-label={t('trend.columns.usage')}>{formatNumber(row.usageCount, locale)}</td>
                    <td data-label={t('trend.columns.visibility')}>
                      <StatusPill
                        tone={row.visible ? 'success' : 'muted'}
                        label={t(row.visible ? 'trend.visibility.visible' : 'trend.visibility.hidden')}
                        dot
                      />
                    </td>
                    {canManage && (
                      <td data-label={t('trend.columns.actions')}>
                        <div className="trend-actions">
                          <button
                            id={rowControlId(row, 'up')}
                            type="button"
                            className="cortex-admin-btn cortex-admin-btn--sm"
                            aria-label={t('trend.actions.moveUp', { name: nameOf(row) })}
                            aria-disabled={busy || index === 0}
                            onClick={() => index > 0 && move(index, -1)}
                          >
                            {t('trend.actions.up')}
                          </button>
                          <button
                            id={rowControlId(row, 'down')}
                            type="button"
                            className="cortex-admin-btn cortex-admin-btn--sm"
                            aria-label={t('trend.actions.moveDown', { name: nameOf(row) })}
                            aria-disabled={busy || index === rows.length - 1}
                            onClick={() => index < rows.length - 1 && move(index, 1)}
                          >
                            {t('trend.actions.down')}
                          </button>
                          <button
                            id={rowControlId(row, 'visibility')}
                            type="button"
                            className="cortex-admin-btn cortex-admin-btn--sm"
                            aria-label={t(row.visible ? 'trend.visibility.hideRow' : 'trend.visibility.showRow', {
                              name: nameOf(row),
                            })}
                            aria-disabled={busy}
                            onClick={() => setVisible(row, !row.visible)}
                          >
                            {t(row.visible ? 'trend.visibility.hide' : 'trend.visibility.show')}
                          </button>
                          <button
                            id={rowControlId(row, 'edit')}
                            type="button"
                            className="cortex-admin-btn cortex-admin-btn--sm"
                            aria-label={t('trend.actions.editRow', { name: nameOf(row) })}
                            aria-disabled={busy}
                            onClick={() => !busy && setDialog({ mode: 'edit', kind, row })}
                          >
                            {t('trend.actions.edit')}
                          </button>
                          <button
                            id={rowControlId(row, 'delete')}
                            type="button"
                            className="cortex-admin-btn cortex-admin-btn--sm cortex-admin-btn--danger"
                            aria-label={t('trend.actions.deleteRow', { name: nameOf(row) })}
                            aria-disabled={busy}
                            onClick={() => !busy && setConfirming({ kind, row })}
                          >
                            {t('trend.actions.delete')}
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </TableRegion>
        )}
      </div>

      {dialog && (
        <TaxonomyEntryDialog
          key={dialog.mode === 'edit' ? dialog.row.id : 'add'}
          kind={dialog.kind}
          entry={dialog.mode === 'edit' ? { ...dialog.row } : undefined}
          onSubmit={(names) => submitEntry(dialog, names)}
          onClose={() =>
            closeDialog(
              dialog.mode === 'edit'
                ? rowControlId(dialog.row, 'edit')
                : addId(dialog.kind === 'category' ? 'categories' : 'tags'),
            )
          }
        />
      )}
      {confirming && (
        <TaxonomyDeleteDialog
          title={t(confirming.kind === 'category' ? 'trend.delete.categoryTitle' : 'trend.delete.tagTitle', {
            name: nameOf(confirming.row),
          })}
          body={deleteBody(confirming.row)}
          pending={deleteCategory.isPending || deleteTag.isPending}
          onConfirm={confirmDelete}
          onClose={() => closeDialog(rowControlId(confirming.row, 'delete'))}
        />
      )}
    </div>
  );
}
