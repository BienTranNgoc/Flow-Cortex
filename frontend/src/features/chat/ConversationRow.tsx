import type { ChatConversation } from '@flow-cortex/api-client';
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { chatProblem } from './chatProblem';
import { MAX_TITLE_LENGTH, validateTitle } from './chatTitle';
import { useRenameConversation } from './useConversations';

interface ConversationRowProps {
  conversation: ChatConversation;
  active: boolean;
  onOpen: () => void;
  onDelete: () => void;
}

export function ConversationRow({ conversation, active, onOpen, onDelete }: Readonly<ConversationRowProps>) {
  const { t } = useTranslation();
  const rename = useRenameConversation();
  const inputId = useId();
  const noteId = useId();
  const renameButton = useRef<HTMLButtonElement>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const id = conversation.id ?? '';
  const title = conversation.title ?? '';
  const check = validateTitle(draft);

  const startEdit = () => {
    rename.reset();
    setDraft(title);
    setEditing(true);
  };

  const stopEdit = () => {
    setEditing(false);
    // The input is about to unmount; focus goes back to the control that opened it.
    requestAnimationFrame(() => renameButton.current?.focus());
  };

  const save = () => {
    if (!check.ok) return;
    if (check.title === title) {
      stopEdit();
      return;
    }
    rename.mutate({ id, title: check.title }, { onSuccess: stopEdit });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      stopEdit();
    } else if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
      event.preventDefault();
      save();
    }
  };

  if (editing) {
    return (
      <li className="chat-row chat-row--editing">
        <label className="chat-sr-only" htmlFor={inputId}>
          {t('chat.row.renameLabel')}
        </label>
        <input
          id={inputId}
          className="chat-row__input"
          value={draft}
          autoFocus
          aria-describedby={noteId}
          aria-invalid={!check.ok || undefined}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <p id={noteId} className="chat-row__note">
          <span className={check.ok ? undefined : 'chat-counter--over'}>
            {t('chat.row.counter', { length: check.length, max: MAX_TITLE_LENGTH })}
          </span>
          {!check.ok && (
            <span role="alert">
              {' '}
              {check.reason === 'empty'
                ? t('chat.row.titleEmpty')
                : t('chat.row.titleTooLong', { max: MAX_TITLE_LENGTH })}
            </span>
          )}
          {rename.isError && (
            <span role="alert"> {chatProblem(rename.error, t).message || t('chat.row.renameFailed')}</span>
          )}
        </p>
        <div className="chat-row__actions">
          <button
            type="button"
            className="chat-button chat-button--primary"
            disabled={!check.ok || rename.isPending}
            onClick={save}
          >
            {t('chat.row.save')}
          </button>
          <button type="button" className="chat-button" onClick={stopEdit}>
            {t('chat.row.cancel')}
          </button>
        </div>
      </li>
    );
  }

  return (
    <li className={active ? 'chat-row chat-row--active' : 'chat-row'}>
      <Link
        to={`/chat/${id}`}
        className="chat-row__link"
        aria-current={active ? 'page' : undefined}
        onClick={onOpen}
      >
        {title}
      </Link>
      <div className="chat-row__actions">
        <button
          ref={renameButton}
          type="button"
          className="chat-button chat-button--quiet"
          aria-label={`${t('chat.row.rename')}: ${title}`}
          onClick={startEdit}
        >
          {t('chat.row.rename')}
        </button>
        <button
          type="button"
          className="chat-button chat-button--quiet"
          aria-label={`${t('chat.row.delete')}: ${title}`}
          onClick={onDelete}
        >
          {t('chat.row.delete')}
        </button>
      </div>
    </li>
  );
}
