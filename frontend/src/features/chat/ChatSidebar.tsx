import type { ChatConversationListItem } from '@flow-cortex/api-client';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { ConversationRow } from './ConversationRow';
import { DeleteDialog } from './DeleteDialog';
import { FolderNameDialog } from './FolderNameDialog';
import { useConversationPages, useFolders } from './history/useChatHistory';
import { MoveToFolderDialog } from './MoveToFolderDialog';
import { RECENT_FILTERS, useDeleteConversation, type ConversationFilters } from './useConversations';

const PINNED_FILTERS: ConversationFilters = { pinned: 'only' };

interface ChatSidebarProps {
  activeId: string | undefined;
  /** Below 1024px the sidebar is a drawer. */
  open: boolean;
  onClose: () => void;
}

interface ListHandlers {
  activeId: string | undefined;
  onOpen: () => void;
  onMove: (conversation: ChatConversationListItem) => void;
  onDelete: (conversation: ChatConversationListItem) => void;
}

/** Rows of one filtered view, paged by "Load more"; every state the request can be in is shown. */
function ConversationList({
  filters,
  emptyText,
  handlers,
}: Readonly<{
  filters: ConversationFilters;
  emptyText?: string | undefined;
  handlers: ListHandlers;
}>) {
  const { t } = useTranslation();
  const pages = useConversationPages(filters);
  // A page fetched after another write can repeat a row that moved; each id shows once.
  const rows = useMemo(() => {
    const byId = new Map<string, ChatConversationListItem>();
    for (const page of pages.data?.pages ?? []) {
      for (const item of page.items ?? []) byId.set(item.id ?? '', item);
    }
    return [...byId.values()];
  }, [pages.data]);

  if (pages.isPending) return <p role="status">{t('chat.list.loading')}</p>;
  if (pages.isError && !pages.data) {
    return (
      <div role="alert" className="chat-state">
        <p>{t('chat.list.loadFailed')}</p>
        <button type="button" className="chat-button" onClick={() => void pages.refetch()}>
          {t('chat.list.retry')}
        </button>
      </div>
    );
  }
  if (pages.isSuccess && rows.length === 0) {
    return emptyText ? <p className="chat-sidebar__empty">{emptyText}</p> : null;
  }
  return (
    <>
      <ul className="chat-list">
        {rows.map((conversation) => (
          <ConversationRow
            key={conversation.id}
            conversation={conversation}
            active={conversation.id === handlers.activeId}
            onOpen={handlers.onOpen}
            onMove={handlers.onMove}
            onDelete={handlers.onDelete}
          />
        ))}
      </ul>
      {pages.isFetchNextPageError && <p role="alert">{t('chat.list.loadMoreFailed')}</p>}
      {pages.hasNextPage && (
        <button
          type="button"
          className="chat-button chat-button--quiet chat-load-more"
          aria-disabled={pages.isFetchingNextPage || undefined}
          aria-busy={pages.isFetchingNextPage || undefined}
          onClick={() => {
            if (!pages.isFetchingNextPage) void pages.fetchNextPage();
          }}
        >
          {pages.isFetchingNextPage ? t('chat.list.loadingMore') : t('chat.list.loadMore')}
        </button>
      )}
    </>
  );
}

function FolderList({
  handlers,
  onCreate,
}: Readonly<{ handlers: ListHandlers; onCreate: () => void }>) {
  const { t } = useTranslation();
  const folders = useFolders();
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());

  const toggle = (id: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  return (
    <>
      {folders.isPending && <p role="status">{t('chat.folders.loading')}</p>}
      {folders.isError && (
        <div role="alert" className="chat-state">
          <p>{t('chat.folders.loadFailed')}</p>
          <button type="button" className="chat-button" onClick={() => void folders.refetch()}>
            {t('chat.folders.retry')}
          </button>
        </div>
      )}
      {folders.isSuccess && folders.data.length === 0 && (
        <p className="chat-sidebar__empty">{t('chat.folders.empty')}</p>
      )}
      {folders.isSuccess && folders.data.length > 0 && (
        <ul className="chat-list">
          {folders.data.map((folder) => {
            const id = folder.id ?? '';
            const open = expanded.has(id);
            const panelId = `chat-folder-${id}`;
            return (
              <li key={id} className="chat-folder">
                <button
                  type="button"
                  className="chat-folder__toggle"
                  aria-expanded={open}
                  aria-controls={open ? panelId : undefined}
                  onClick={() => toggle(id)}
                >
                  <span aria-hidden="true" className="chat-folder__chevron" />
                  <span className="chat-folder__name">{folder.name}</span>
                  <span className="chat-folder__count">{folder.conversationCount ?? 0}</span>
                </button>
                {open && (
                  <div id={panelId} className="chat-folder__panel">
                    <ConversationList
                      filters={{ folder: id, pinned: 'exclude' }}
                      emptyText={t('chat.folders.folderEmpty')}
                      handlers={handlers}
                    />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <button type="button" className="chat-button chat-button--quiet chat-folder__new" onClick={onCreate}>
        {t('chat.folders.new')}
      </button>
    </>
  );
}

export function ChatSidebar({ activeId, open, onClose }: Readonly<ChatSidebarProps>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const remove = useDeleteConversation();
  const pinned = useConversationPages(PINNED_FILTERS);
  const folders = useFolders();
  const [pendingDelete, setPendingDelete] = useState<ChatConversationListItem | null>(null);
  const [moving, setMoving] = useState<ChatConversationListItem | null>(null);
  const [creatingFolder, setCreatingFolder] = useState(false);
  const nothingPinned = pinned.isSuccess && (pinned.data.pages[0]?.items ?? []).length === 0;
  const inFolders = (folders.data ?? []).some((folder) => (folder.conversationCount ?? 0) > 0);
  // An empty Recent says "none yet" only when nothing is pinned or filed either.
  const recentEmpty = nothingPinned ? t(inFolders ? 'chat.list.recentEmpty' : 'chat.list.empty') : undefined;

  const handlers: ListHandlers = {
    activeId,
    onOpen: onClose,
    onMove: setMoving,
    onDelete: (conversation) => {
      remove.reset();
      setPendingDelete(conversation);
    },
  };

  const confirmDelete = () => {
    if (!pendingDelete) return;
    const id = pendingDelete.id ?? '';
    remove.mutate(id, {
      onSuccess: () => {
        setPendingDelete(null);
        if (id === activeId) void navigate('/chat');
      },
    });
  };

  return (
    <>
      <nav
        id="chat-sidebar"
        className={open ? 'chat-sidebar is-open' : 'chat-sidebar'}
        aria-label={t('chat.list.label')}
      >
        <div className="chat-sidebar__head">
          <h2>{t('chat.list.heading')}</h2>
          <button type="button" className="chat-button chat-drawer-close" onClick={onClose}>
            {t('chat.list.close')}
          </button>
        </div>
        <button
          type="button"
          className="chat-button chat-button--primary"
          onClick={() => {
            onClose();
            void navigate('/chat');
          }}
        >
          {t('chat.list.new')}
        </button>
        <Link to="/chat/history" className="chat-link" onClick={onClose}>
          {t('chat.list.history')}
        </Link>
        {!nothingPinned && (
          <section aria-labelledby="chat-sidebar-pinned" className="chat-section">
            <h3 id="chat-sidebar-pinned" className="chat-section__title">
              {t('chat.list.pinned')}
            </h3>
            <ConversationList filters={PINNED_FILTERS} handlers={handlers} />
          </section>
        )}
        <section aria-labelledby="chat-sidebar-folders" className="chat-section">
          <h3 id="chat-sidebar-folders" className="chat-section__title">
            {t('chat.list.folders')}
          </h3>
          <FolderList handlers={handlers} onCreate={() => setCreatingFolder(true)} />
        </section>
        <section aria-labelledby="chat-sidebar-recent" className="chat-section">
          <h3 id="chat-sidebar-recent" className="chat-section__title">
            {t('chat.list.recent')}
          </h3>
          <ConversationList filters={RECENT_FILTERS} emptyText={recentEmpty} handlers={handlers} />
        </section>
      </nav>
      <DeleteDialog
        title={pendingDelete?.title ?? null}
        deleting={remove.isPending}
        failed={remove.isError}
        onKeep={() => setPendingDelete(null)}
        onDelete={confirmDelete}
      />
      <MoveToFolderDialog conversation={moving} onClose={() => setMoving(null)} />
      <FolderNameDialog
        target={creatingFolder ? { kind: 'create' } : null}
        onClose={() => setCreatingFolder(false)}
      />
    </>
  );
}
