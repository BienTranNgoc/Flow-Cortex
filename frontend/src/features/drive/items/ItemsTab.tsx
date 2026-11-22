import { ApiError, type DriveItemResponse } from '@flow-cortex/api-client';
import { Plus } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusBlock } from '../../../app/StatusBlock';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { problemDetailOf } from '../../../lib/problemMessage';
import { CONTENT_TYPES, type ContentType } from '../model/driveRoles';
import { pageAfterShrink } from '../model/pageWindow';
import { ConfirmDialog } from '../shared/ConfirmDialog';
import { DriveFailed, DriveLoading } from '../shared/DriveStates';
import { Pagination } from '../shared/Pagination';
import { PreviewDialog } from '../shared/PreviewDialog';
import { EditItemDialog } from './EditItemDialog';
import { ItemCard } from './ItemCard';
import { SuggestionsSection } from './SuggestionsSection';
import { TextItemForm } from './TextItemForm';
import { UploadItemForm } from './UploadItemForm';
import { ITEMS_PAGE_SIZE, useDeleteDriveItem, useDriveItemsQuery } from './useDriveItems';
import '../shared/DriveShared.css';
import './DriveItems.css';

/** The member's saved items, one content type at a time, with the suggestions from recent runs below. */
export function ItemsTab({ kept, onKept }: { kept: ReadonlySet<string>; onKept: (suggestionId: string) => void }) {
  const { t } = useTranslation();
  const ids = useId();
  const locale = activeLocale();
  const [type, setType] = useState<ContentType>('text');
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<DriveItemResponse | null>(null);
  const [deleting, setDeleting] = useState<DriveItemResponse | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [previewId, setPreviewId] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [focusPanel, setFocusPanel] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const tabRefs = useRef<Partial<Record<ContentType, HTMLButtonElement | null>>>({});
  const query = useDriveItemsQuery(type, page);
  const remove = useDeleteDriveItem();

  // A delete or hide that emptied the last page leaves the page index past the end.
  useEffect(() => {
    const data = query.data;
    if (query.isPlaceholderData || !data || data.items.length > 0 || data.total === 0) return;
    const next = pageAfterShrink(page, data.total, ITEMS_PAGE_SIZE);
    if (next !== page) setPage(next);
  }, [query.data, query.isPlaceholderData, page]);

  // Runs after the confirm dialog has handed focus back to a button the reload may have removed.
  useEffect(() => {
    if (focusPanel) {
      panelRef.current?.focus();
      setFocusPanel(false);
    }
  }, [focusPanel]);

  const items = query.data?.items ?? [];
  const preview = items.find((item) => item.id === previewId);

  function selectType(next: ContentType) {
    setType(next);
    setPage(0);
    setNotice('');
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const index = CONTENT_TYPES.indexOf(type);
    const moves: Record<string, number> = {
      ArrowRight: (index + 1) % CONTENT_TYPES.length,
      ArrowLeft: (index - 1 + CONTENT_TYPES.length) % CONTENT_TYPES.length,
      Home: 0,
      End: CONTENT_TYPES.length - 1,
    };
    const target = moves[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const next = CONTENT_TYPES[target] as ContentType;
    selectType(next);
    tabRefs.current[next]?.focus();
  }

  function saved(name: string) {
    setAdding(false);
    setEditing(null);
    setNotice(t('driveItems.notice.saved', { name }));
  }

  function confirmDelete() {
    if (!deleting) return;
    const { id, name } = deleting;
    setDeleteError(null);
    remove.mutate(id, {
      onSuccess: () => {
        setDeleting(null);
        setNotice(t('driveItems.delete.done', { name }));
        setFocusPanel(true);
      },
      onError: (error) => {
        if (error instanceof ApiError && error.status === 404) {
          setDeleting(null);
          setNotice(t('driveItems.delete.alreadyGone', { name }));
          setFocusPanel(true);
          return;
        }
        setDeleteError(problemDetailOf(error) ?? t('driveItems.delete.failed'));
      },
    });
  }

  function cancelDelete() {
    remove.reset();
    setDeleteError(null);
    setDeleting(null);
  }

  const addLabel = t(`driveItems.add.${type}`);
  const openAdd = () => setAdding(true);

  let body;
  if (query.isPending) {
    body = <DriveLoading />;
  } else if (query.isError && !query.data) {
    body = (
      <DriveFailed
        message={problemDetailOf(query.error) ?? t('drive.status.failed')}
        onRetry={() => void query.refetch()}
      />
    );
  } else if (items.length === 0) {
    body = <StatusBlock kind="empty" message={t('driveItems.empty.title')} action={{ label: addLabel, onClick: openAdd }} />;
  } else {
    body = (
      <>
        <p className="drive-items__total">
          {t('driveItems.list.total', { count: query.data?.total ?? 0, n: formatNumber(query.data?.total ?? 0, locale) })}
        </p>
        <ul className="drive-grid" aria-busy={query.isPlaceholderData || undefined}>
          {items.map((item) => (
            <ItemCard
              key={item.id}
              item={item}
              onOpen={(opened) => setPreviewId(opened.id)}
              onEdit={setEditing}
              onDelete={setDeleting}
              onRenew={() => void query.refetch()}
            />
          ))}
        </ul>
        <Pagination
          page={page}
          total={query.data?.total ?? 0}
          size={ITEMS_PAGE_SIZE}
          onPage={setPage}
        />
      </>
    );
  }

  return (
    <div className="drive-items">
      <div className="drive-items__toolbar">
        <div role="tablist" aria-label={t('driveItems.types.label')} className="drive-subtabs">
          {CONTENT_TYPES.map((candidate) => (
            <button
              key={candidate}
              ref={(element) => {
                tabRefs.current[candidate] = element;
              }}
              type="button"
              role="tab"
              id={`${ids}-tab-${candidate}`}
              aria-selected={candidate === type}
              aria-controls={`${ids}-panel`}
              tabIndex={candidate === type ? 0 : -1}
              className="drive-subtab"
              onClick={() => selectType(candidate)}
              onKeyDown={onTabKeyDown}
            >
              {t(`driveItems.types.${candidate}`)}
            </button>
          ))}
        </div>
        <button type="button" className="drive-button drive-button--primary" onClick={openAdd}>
          <Plus aria-hidden="true" />
          {addLabel}
        </button>
      </div>

      <div
        ref={panelRef}
        id={`${ids}-panel`}
        role="tabpanel"
        aria-labelledby={`${ids}-tab-${type}`}
        tabIndex={-1}
        className="drive-items__panel"
      >
        <output className="drive-items__notice">{notice}</output>
        {body}
      </div>

      <SuggestionsSection kept={kept} onKept={onKept} />

      <TextItemForm open={adding && type === 'text'} onClose={() => setAdding(false)} onSaved={saved} />
      {type !== 'text' && (
        <UploadItemForm open={adding} contentType={type} onClose={() => setAdding(false)} onSaved={saved} />
      )}
      <EditItemDialog item={editing} onClose={() => setEditing(null)} onSaved={saved} />
      <PreviewDialog
        open={preview !== undefined}
        title={preview?.name ?? ''}
        kind={preview?.contentType ?? 'text'}
        text={preview?.textValue}
        url={preview?.url}
        urlExpiresAt={preview?.urlExpiresAt}
        onRenew={() => void query.refetch()}
        onClose={() => setPreviewId(null)}
      />
      <ConfirmDialog
        open={deleting !== null}
        title={t('driveItems.delete.title')}
        message={t('driveItems.delete.message', { name: deleting?.name ?? '' })}
        confirmLabel={t('driveItems.delete.confirm')}
        busyLabel={t('driveItems.delete.busy')}
        error={deleteError}
        busy={remove.isPending}
        onCancel={cancelDelete}
        onConfirm={confirmDelete}
      />
    </div>
  );
}
