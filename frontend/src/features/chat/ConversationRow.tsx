import type { ChatConversationListItem } from '@flow-cortex/api-client';
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { chatProblem } from './chatProblem';
import { MAX_TITLE_LENGTH, validateTitle } from './chatTitle';
import { flattenPreview } from './history/previewText';
import { formatConversationTime, type Translate } from './history/sessionTime';
import { usePin } from './history/useChatHistory';
import { RowMenu } from './RowMenu';
import { useRenameConversation } from './useConversations';

interface ConversationRowProps {
  conversation: ChatConversationListItem;
  active: boolean;
  onOpen: () => void;
  onMove: (conversation: ChatConversationListItem) => void;
  onDelete: (conversation: ChatConversationListItem) => void;
}

export function ConversationRow({
  conversation,
  active,
  onOpen,
  onMove,
  onDelete,
}: Readonly<ConversationRowProps>) {
  const { t } = useTranslation();
  const rename = useRenameConversation();
  const pin = usePin();
  const inputId = useId();
  const noteId = useId();
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const id = conversation.id ?? '';
  const title = conversation.title ?? '';
  const pinned = conversation.pinned === true;
  const check = validateTitle(draft);
  // The time helper names keys of the chat locale tree, which `t` addresses under `chat.`.
  const tChat: Translate = (key, options) => t(`chat.${key}`, options ?? {});
  const preview = conversation.preview ? flattenPreview(conversation.preview, conversation.previewTruncated === true) : '';

  const startEdit = () => {
    rename.reset();
    setDraft(title);
    setEditing(true);
  };

  const stopEdit = () => {
    setEditing(false);
    // The input is about to unmount; focus goes back to the control that opened it.
    requestAnimationFrame(() => menuTrigger.current?.focus());
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
        <span className="chat-row__title">{title}</span>
        {preview && <span className="chat-row__preview">{preview}</span>}
      </Link>
      {conversation.lastActivityAt && (
        <span className="chat-row__time">
          {formatConversationTime(conversation.lastActivityAt, new Date(), tChat, pinned)}
        </span>
      )}
      <RowMenu
        label={`${t('chat.row.actions')}: ${title}`}
        pinned={pinned}
        pinBusy={pin.isPending}
        triggerRef={menuTrigger}
        onRename={startEdit}
        onTogglePin={() => {
          pin.reset();
          pin.mutate({ id, pinned: !pinned });
        }}
        onMove={() => onMove(conversation)}
        onDelete={() => onDelete(conversation)}
      />
      {pin.isError && (
        <p role="alert" className="chat-row__note chat-row__problem">
          {chatProblem(pin.error, t).message}
        </p>
      )}
    </li>
  );
}
