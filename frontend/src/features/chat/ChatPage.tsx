import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import './chat.css';
import { ConversationRow } from './ConversationRow';
import { DeleteDialog } from './DeleteDialog';
import { NewThreadPane } from './NewThreadPane';
import { ThreadPane } from './ThreadPane';
import { useConversationList, useDeleteConversation } from './useConversations';

export function ChatPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { conversationId } = useParams();
  const list = useConversationList();
  const remove = useDeleteConversation();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<{ id: string; title: string } | null>(null);
  const conversations = list.data?.items ?? [];

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  const startNew = () => {
    setDrawerOpen(false);
    void navigate('/chat');
  };

  const confirmDelete = () => {
    if (!pendingDelete) return;
    const { id } = pendingDelete;
    remove.mutate(id, {
      onSuccess: () => {
        setPendingDelete(null);
        if (id === conversationId) void navigate('/chat');
      },
    });
  };

  return (
    <div className="chat-page">
      <h1 className="chat-sr-only">{t('chat.page.title')}</h1>
      <button
        type="button"
        className="chat-button chat-drawer-toggle"
        aria-expanded={drawerOpen}
        aria-controls="chat-sidebar"
        onClick={() => setDrawerOpen(true)}
      >
        {t('chat.list.open')}
      </button>
      {drawerOpen && (
        <button
          type="button"
          className="chat-backdrop"
          tabIndex={-1}
          aria-hidden="true"
          onClick={() => setDrawerOpen(false)}
        />
      )}
      <nav
        id="chat-sidebar"
        className={drawerOpen ? 'chat-sidebar is-open' : 'chat-sidebar'}
        aria-label={t('chat.list.label')}
      >
        <div className="chat-sidebar__head">
          <h2>{t('chat.list.heading')}</h2>
          <button
            type="button"
            className="chat-button chat-drawer-close"
            onClick={() => setDrawerOpen(false)}
          >
            {t('chat.list.close')}
          </button>
        </div>
        <button type="button" className="chat-button chat-button--primary" onClick={startNew}>
          {t('chat.list.new')}
        </button>
        {list.isPending && <p role="status">{t('chat.list.loading')}</p>}
        {list.isError && (
          <div role="alert" className="chat-state">
            <p>{t('chat.list.loadFailed')}</p>
            <button type="button" className="chat-button" onClick={() => void list.refetch()}>
              {t('chat.list.retry')}
            </button>
          </div>
        )}
        {list.isSuccess && conversations.length === 0 && (
          <p className="chat-sidebar__empty">{t('chat.list.empty')}</p>
        )}
        <ul className="chat-list">
          {conversations.map((conversation) => (
            <ConversationRow
              key={conversation.id}
              conversation={conversation}
              active={conversation.id === conversationId}
              onOpen={() => setDrawerOpen(false)}
              onDelete={() => {
                remove.reset();
                setPendingDelete({ id: conversation.id ?? '', title: conversation.title ?? '' });
              }}
            />
          ))}
        </ul>
      </nav>
      <section className="chat-pane">
        {conversationId ? (
          <ThreadPane
            key={conversationId}
            conversationId={conversationId}
            chatEnabled={list.data?.chatEnabled !== false}
          />
        ) : (
          <NewThreadPane chatEnabled={list.data?.chatEnabled !== false} />
        )}
      </section>
      <DeleteDialog
        title={pendingDelete?.title ?? null}
        deleting={remove.isPending}
        failed={remove.isError}
        onKeep={() => setPendingDelete(null)}
        onDelete={confirmDelete}
      />
    </div>
  );
}
