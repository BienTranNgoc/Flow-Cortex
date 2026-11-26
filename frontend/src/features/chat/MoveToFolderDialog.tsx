import type { ChatConversationListItem } from '@flow-cortex/api-client';
import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { chatProblem, chatProblemCode } from './chatProblem';
import { useFolders, useMoveToFolder } from './history/useChatHistory';

interface MoveToFolderDialogProps {
  /** The conversation to file, or `null` while the dialog is closed. */
  conversation: ChatConversationListItem | null;
  onClose: () => void;
}

export function MoveToFolderDialog({ conversation, onClose }: Readonly<MoveToFolderDialogProps>) {
  return conversation ? <MoveForm conversation={conversation} onClose={onClose} /> : null;
}

/** Mounted only while open: it opens as a native modal and gives focus back to what had it. */
function MoveForm({
  conversation,
  onClose,
}: Readonly<{ conversation: ChatConversationListItem; onClose: () => void }>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const folders = useFolders();
  const move = useMoveToFolder();
  const currentId = conversation.folderId ?? null;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog.showModal();
    return () => {
      dialog.close();
      if (opener?.isConnected) opener.focus();
    };
  }, []);

  const choose = (folderId: string | null) => {
    if (move.isPending) return;
    if (folderId === currentId) {
      onClose();
      return;
    }
    move.mutate({ id: conversation.id ?? '', folderId }, { onSuccess: onClose });
  };

  const option = (folderId: string | null, name: string, count?: number) => {
    const current = folderId === currentId;
    return (
      <li key={folderId ?? 'none'}>
        <button
          type="button"
          className="chat-move__option"
          aria-current={current || undefined}
          aria-disabled={move.isPending || undefined}
          onClick={() => choose(folderId)}
        >
          <span className="chat-move__name">{name}</span>
          {count !== undefined && <span className="chat-move__count">{count}</span>}
          {current && <span className="chat-move__current">{t('chat.move.current')}</span>}
        </button>
      </li>
    );
  };

  const problem = move.isError
    ? chatProblemCode(move.error) === 'chat-folder-not-found'
      ? t('chat.folders.gone')
      : chatProblem(move.error, t).message
    : null;

  return (
    <dialog
      ref={dialogRef}
      className="chat-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      aria-busy={move.isPending || undefined}
      onCancel={(event) => {
        event.preventDefault();
        if (!move.isPending) onClose();
      }}
    >
      <h2 id={titleId}>{t('chat.move.title')}</h2>
      <p id={textId}>{t('chat.move.text', { title: conversation.title ?? '' })}</p>
      {folders.isPending && <p role="status">{t('chat.folders.loading')}</p>}
      {folders.isError && (
        <div role="alert" className="chat-state">
          <p>{t('chat.folders.loadFailed')}</p>
          <button type="button" className="chat-button" onClick={() => void folders.refetch()}>
            {t('chat.folders.retry')}
          </button>
        </div>
      )}
      {folders.isSuccess && (
        <ul className="chat-move__list">
          {option(null, t('chat.move.none'))}
          {folders.data.map((folder) => option(folder.id ?? '', folder.name ?? '', folder.conversationCount))}
        </ul>
      )}
      {move.isPending && <p role="status">{t('chat.move.moving')}</p>}
      {problem && (
        <p role="alert" className="form-error">
          {problem}
        </p>
      )}
      <div className="chat-dialog__actions">
        <button
          type="button"
          className="chat-button"
          aria-disabled={move.isPending || undefined}
          onClick={() => {
            if (!move.isPending) onClose();
          }}
        >
          {t('chat.move.cancel')}
        </button>
      </div>
    </dialog>
  );
}
