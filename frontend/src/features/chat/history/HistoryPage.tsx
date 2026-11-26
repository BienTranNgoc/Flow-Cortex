import type { ChatConversationList, ChatConversationListItem } from '@flow-cortex/api-client';
import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import '../chat.css';
import { chatProblemCode } from '../chatProblem';
import { ConversationRow } from '../ConversationRow';
import { DeleteDialog } from '../DeleteDialog';
import { FolderNameDialog, type FolderNameTarget } from '../FolderNameDialog';
import { MoveToFolderDialog } from '../MoveToFolderDialog';
import { useDeleteConversation, type ConversationFilters } from '../useConversations';
import { DeleteFolderDialog, FolderCards } from './FolderCards';
import './history.css';
import { dayBoundaries, groupConversations, type SortOrder, type TimeGroup } from './sessionGroups';
import {
  useConversationCounts,
  useConversationPages,
  useDeleteFolder,
  useFolders,
} from './useChatHistory';

/** The server refuses a longer search; the address can hold anything. */
const MAX_QUERY = 100;

interface Address {
  q: string;
  sort: SortOrder;
  folder: string | null;
}

function readAddress(params: URLSearchParams): Address {
  return {
    q: [...(params.get('q') ?? '').trim()].slice(0, MAX_QUERY).join(''),
    sort: params.get('sort') === 'oldest' ? 'oldest' : 'newest',
    folder: params.get('folder') || null,
  };
}

/** Only what differs from the defaults is written, so the plain page has a plain address. */
function addressSearch({ q, sort, folder }: Address): string {
  const params = new URLSearchParams();
  if (q) params.set('q', q);
  if (sort === 'oldest') params.set('sort', 'oldest');
  if (folder) params.set('folder', folder);
  const text = params.toString();
  return text ? `?${text}` : '';
}

const GROUP_LABEL: Record<TimeGroup, string> = {
  today: 'chat.history.group.today',
  last7: 'chat.history.group.last7',
  earlier: 'chat.history.group.earlier',
};

/** A page fetched after another write can repeat a row that moved; each id shows once. */
function uniqueRows(pages: readonly ChatConversationList[] | undefined): ChatConversationListItem[] {
  const byId = new Map<string, ChatConversationListItem>();
  for (const page of pages ?? []) {
    for (const item of page.items ?? []) byId.set(item.id ?? '', item);
  }
  return [...byId.values()];
}

interface RowHandlers {
  onMove: (conversation: ChatConversationListItem) => void;
  onDelete: (conversation: ChatConversationListItem) => void;
}

// The history page has no drawer for a row to close.
const noop = () => undefined;

function LoadMore({
  fetching,
  failed,
  onMore,
}: Readonly<{ fetching: boolean; failed: boolean; onMore: () => void }>) {
  const { t } = useTranslation();
  return (
    <>
      {failed && <p role="alert">{t('chat.list.loadMoreFailed')}</p>}
      <button
        type="button"
        className="chat-button chat-button--quiet chat-load-more"
        aria-disabled={fetching || undefined}
        aria-busy={fetching || undefined}
        onClick={() => {
          if (!fetching) onMore();
        }}
      >
        {fetching ? t('chat.list.loadingMore') : t('chat.list.loadMore')}
      </button>
    </>
  );
}

function Group({
  titleKey,
  count,
  rows,
  handlers,
}: Readonly<{
  titleKey: string;
  count: number | undefined;
  rows: readonly ChatConversationListItem[];
  handlers: RowHandlers;
}>) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className="chat-section">
      <h2 id={titleId} className="chat-section__title">
        {t(titleKey)}
        {count !== undefined && <span className="chat-history__count">{count}</span>}
      </h2>
      <ul className="chat-list">
        {rows.map((conversation) => (
          <ConversationRow
            key={conversation.id}
            conversation={conversation}
            active={false}
            onOpen={noop}
            onMove={handlers.onMove}
            onDelete={handlers.onDelete}
          />
        ))}
      </ul>
    </section>
  );
}

/**
 * The Pinned group and the time groups of one view. The two lists and the counts share `q`, `folder`
 * and the day boundaries, so a header count and the rows under it describe the same set.
 */
function HistoryResults({
  address,
  handlers,
}: Readonly<{ address: Address; handlers: RowHandlers }>) {
  const { t } = useTranslation();
  // Fixed for the life of the view: the counts request and the grouping must use the same instants.
  const [boundaries] = useState(() => dayBoundaries(new Date()));
  const scope = {
    ...(address.q ? { q: address.q } : {}),
    ...(address.folder ? { folder: address.folder } : {}),
  };
  const filters: ConversationFilters = { ...scope, sort: address.sort };
  const pinnedPages = useConversationPages({ ...filters, pinned: 'only' });
  const restPages = useConversationPages({ ...filters, pinned: 'exclude' });
  const counts = useConversationCounts({
    ...scope,
    todayStart: boundaries.todayStart.toISOString(),
    weekStart: boundaries.weekStart.toISOString(),
  });
  const pinnedRows = useMemo(() => uniqueRows(pinnedPages.data?.pages), [pinnedPages.data]);
  const restRows = useMemo(() => uniqueRows(restPages.data?.pages), [restPages.data]);
  // The generated type marks `lastActivityAt` optional; the server always sends it.
  const groups = useMemo(
    () =>
      groupConversations(
        restRows.map((row) => ({ row, lastActivityAt: row.lastActivityAt ?? '' })),
        boundaries,
        address.sort,
      ),
    [restRows, boundaries, address.sort],
  );
  const countOf: Record<TimeGroup, number | undefined> = {
    today: counts.data?.today,
    last7: counts.data?.last7Days,
    earlier: counts.data?.earlier,
  };

  if (pinnedPages.isPending || restPages.isPending) return <p role="status">{t('chat.list.loading')}</p>;
  if ((pinnedPages.isError && !pinnedPages.data) || (restPages.isError && !restPages.data)) {
    const retry = () => {
      if (pinnedPages.isError && !pinnedPages.data) void pinnedPages.refetch();
      if (restPages.isError && !restPages.data) void restPages.refetch();
    };
    return (
      <div role="alert" className="chat-state">
        <p>{t('chat.list.loadFailed')}</p>
        <button type="button" className="chat-button" onClick={retry}>
          {t('chat.list.retry')}
        </button>
      </div>
    );
  }
  if (pinnedRows.length === 0 && restRows.length === 0) {
    let empty = t('chat.list.empty');
    if (address.q) empty = t('chat.history.noMatch', { q: address.q });
    else if (address.folder) empty = t('chat.folders.folderEmpty');
    return <p className="chat-sidebar__empty">{empty}</p>;
  }

  return (
    <>
      {pinnedRows.length > 0 && (
        <>
          <Group
            titleKey="chat.list.pinned"
            count={counts.data?.pinned}
            rows={pinnedRows}
            handlers={handlers}
          />
          {pinnedPages.hasNextPage && (
            <LoadMore
              fetching={pinnedPages.isFetchingNextPage}
              failed={pinnedPages.isFetchNextPageError}
              onMore={() => void pinnedPages.fetchNextPage()}
            />
          )}
        </>
      )}
      {groups.map(({ group, items }) => (
        <Group
          key={group}
          titleKey={GROUP_LABEL[group]}
          count={countOf[group]}
          rows={items.map((item) => item.row)}
          handlers={handlers}
        />
      ))}
      {restPages.hasNextPage && (
        <LoadMore
          fetching={restPages.isFetchingNextPage}
          failed={restPages.isFetchNextPageError}
          onMore={() => void restPages.fetchNextPage()}
        />
      )}
    </>
  );
}

/** Submits on Enter or the button; the box follows the address when Back or a link changes `q`. */
function SearchBox({
  query,
  sort,
  onSearch,
  onSort,
}: Readonly<{
  query: string;
  sort: SortOrder;
  onSearch: (q: string) => void;
  onSort: (sort: SortOrder) => void;
}>) {
  const { t } = useTranslation();
  const inputId = useId();
  const sortId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState(query);
  const [synced, setSynced] = useState(query);
  if (synced !== query) {
    setSynced(query);
    setDraft(query);
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSearch(draft.trim());
  };

  return (
    <form role="search" className="chat-history__tools" onSubmit={submit}>
      <div className="chat-history__search">
        <label htmlFor={inputId} className="chat-sr-only">
          {t('chat.history.searchLabel')}
        </label>
        <input
          ref={input}
          id={inputId}
          className="chat-row__input chat-history__input"
          value={draft}
          maxLength={MAX_QUERY}
          autoComplete="off"
          enterKeyHint="search"
          onChange={(event) => setDraft(event.target.value)}
        />
        {(draft !== '' || query !== '') && (
          <button
            type="button"
            className="chat-button chat-button--quiet"
            onClick={() => {
              setDraft('');
              onSearch('');
              input.current?.focus();
            }}
          >
            {t('chat.history.clear')}
          </button>
        )}
        <button type="submit" className="chat-button chat-button--primary">
          {t('chat.history.search')}
        </button>
      </div>
      <div className="chat-history__sort">
        <label htmlFor={sortId}>{t('chat.history.sortLabel')}</label>
        <select
          id={sortId}
          className="chat-row__input"
          value={sort}
          onChange={(event) => onSort(event.target.value === 'oldest' ? 'oldest' : 'newest')}
        >
          <option value="newest">{t('chat.history.sortNewest')}</option>
          <option value="oldest">{t('chat.history.sortOldest')}</option>
        </select>
      </div>
    </form>
  );
}

export function HistoryPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const address = readAddress(params);
  const folderId = address.folder;
  const folders = useFolders();
  const removeConversation = useDeleteConversation();
  const removeFolder = useDeleteFolder();
  const [pendingDelete, setPendingDelete] = useState<ChatConversationListItem | null>(null);
  const [moving, setMoving] = useState<ChatConversationListItem | null>(null);
  const [renaming, setRenaming] = useState<FolderNameTarget | null>(null);
  const [deletingFolder, setDeletingFolder] = useState<{ id: string; name: string } | null>(null);
  // The folder the address named that the list has confirmed, so a later absence means "deleted".
  const [seenFolder, setSeenFolder] = useState<string | null>(null);

  const folder = folderId ? folders.data?.find((candidate) => candidate.id === folderId) : undefined;
  const foldersSettled = folders.isSuccess && !folders.isFetching;

  const write = (patch: Partial<Address>) =>
    setParams(new URLSearchParams(addressSearch({ ...address, ...patch })), { replace: true });

  useEffect(() => {
    if (!folderId) return;
    if (folder) {
      if (seenFolder !== folderId) setSeenFolder(folderId);
    } else if (foldersSettled && seenFolder !== folderId) {
      // An address naming a folder that does not exist shows the whole history instead.
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.delete('folder');
          return next;
        },
        { replace: true },
      );
    }
  }, [folderId, folder, foldersSettled, seenFolder, setParams]);

  const handlers: RowHandlers = {
    onMove: setMoving,
    onDelete: (conversation) => {
      removeConversation.reset();
      setPendingDelete(conversation);
    },
  };

  const confirmDeleteConversation = () => {
    if (!pendingDelete) return;
    removeConversation.mutate(pendingDelete.id ?? '', { onSuccess: () => setPendingDelete(null) });
  };

  const confirmDeleteFolder = () => {
    if (!deletingFolder) return;
    const { id } = deletingFolder;
    // Gone in another tab already: what the member asked for holds.
    const finish = () => {
      setDeletingFolder(null);
      if (id === folderId) write({ folder: null });
    };
    removeFolder.mutate(id, {
      onSuccess: finish,
      onError: (error) => {
        if (chatProblemCode(error) === 'chat-folder-not-found') finish();
      },
    });
  };

  const folderView = () => {
    if (folder) {
      return (
        <>
          <div className="chat-history__folder">
            <Link to={{ search: addressSearch({ ...address, folder: null }) }} className="chat-link">
              {t('chat.history.back')}
            </Link>
            <h2>{folder.name}</h2>
            <div className="chat-history__folder-actions">
              <button
                type="button"
                className="chat-button"
                onClick={() => setRenaming({ kind: 'rename', id: folderId ?? '', name: folder.name ?? '' })}
              >
                {t('chat.row.rename')}
              </button>
              <button
                type="button"
                className="chat-button chat-button--danger"
                onClick={() => {
                  removeFolder.reset();
                  setDeletingFolder({ id: folderId ?? '', name: folder.name ?? '' });
                }}
              >
                {t('chat.row.delete')}
              </button>
            </div>
          </div>
          <HistoryResults address={address} handlers={handlers} />
        </>
      );
    }
    if (folders.isError && !folders.data) {
      return (
        <div role="alert" className="chat-state">
          <p>{t('chat.folders.loadFailed')}</p>
          <button type="button" className="chat-button" onClick={() => void folders.refetch()}>
            {t('chat.folders.retry')}
          </button>
        </div>
      );
    }
    if (foldersSettled && seenFolder === folderId) {
      return (
        <div role="alert" className="chat-state">
          <p>{t('chat.folders.gone')}</p>
          <Link to={{ search: addressSearch({ ...address, folder: null }) }} className="chat-link">
            {t('chat.history.back')}
          </Link>
        </div>
      );
    }
    return <p role="status">{t('chat.folders.loading')}</p>;
  };

  const wholeHistory = () => (
    <>
      <section aria-labelledby="chat-history-folders" className="chat-section">
        <h2 id="chat-history-folders" className="chat-section__title">
          {t('chat.list.folders')}
        </h2>
        {folders.isPending && <p role="status">{t('chat.folders.loading')}</p>}
        {folders.isError && !folders.data && (
          <div role="alert" className="chat-state">
            <p>{t('chat.folders.loadFailed')}</p>
            <button type="button" className="chat-button" onClick={() => void folders.refetch()}>
              {t('chat.folders.retry')}
            </button>
          </div>
        )}
        {folders.data?.length === 0 && <p className="chat-sidebar__empty">{t('chat.folders.empty')}</p>}
        {folders.data && folders.data.length > 0 && (
          <FolderCards
            folders={folders.data}
            openSearch={(id) => addressSearch({ ...address, folder: id })}
            onRename={(target) =>
              setRenaming({ kind: 'rename', id: target.id ?? '', name: target.name ?? '' })
            }
            onDelete={(target) => {
              removeFolder.reset();
              setDeletingFolder({ id: target.id ?? '', name: target.name ?? '' });
            }}
          />
        )}
      </section>
      <HistoryResults address={address} handlers={handlers} />
    </>
  );

  return (
    <div className="chat-history">
      <div className="chat-history__head">
        <h1>{t('chat.history.title')}</h1>
        <Link to="/chat" className="chat-link">
          {t('chat.history.toChat')}
        </Link>
      </div>
      <SearchBox
        query={address.q}
        sort={address.sort}
        onSearch={(q) => write({ q })}
        onSort={(sort) => write({ sort })}
      />
      {folderId ? folderView() : wholeHistory()}
      <DeleteDialog
        title={pendingDelete?.title ?? null}
        deleting={removeConversation.isPending}
        failed={removeConversation.isError}
        onKeep={() => setPendingDelete(null)}
        onDelete={confirmDeleteConversation}
      />
      <MoveToFolderDialog conversation={moving} onClose={() => setMoving(null)} />
      <FolderNameDialog target={renaming} onClose={() => setRenaming(null)} />
      <DeleteFolderDialog
        name={deletingFolder?.name ?? null}
        deleting={removeFolder.isPending}
        failed={removeFolder.isError}
        onKeep={() => setDeletingFolder(null)}
        onDelete={confirmDeleteFolder}
      />
    </div>
  );
}
