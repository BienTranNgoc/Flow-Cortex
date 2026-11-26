import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import './chat.css';
import { ChatSidebar } from './ChatSidebar';
import { NewThreadPane } from './NewThreadPane';
import { ThreadPane } from './ThreadPane';
import { useConversationList } from './useConversations';

export function ChatPage() {
  const { t } = useTranslation();
  const { conversationId } = useParams();
  // The same cache entry as the sidebar's Recent list, so reading the switch costs no request.
  const recent = useConversationList();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const chatEnabled = recent.data?.pages[0]?.chatEnabled !== false;

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

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
      <ChatSidebar activeId={conversationId} open={drawerOpen} onClose={() => setDrawerOpen(false)} />
      <section className="chat-pane">
        {conversationId ? (
          <ThreadPane key={conversationId} conversationId={conversationId} chatEnabled={chatEnabled} />
        ) : (
          <NewThreadPane chatEnabled={chatEnabled} />
        )}
      </section>
    </div>
  );
}
