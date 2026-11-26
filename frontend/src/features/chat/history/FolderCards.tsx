import type { ChatFolder } from '@flow-cortex/api-client';
import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

interface FolderCardsProps {
  folders: readonly ChatFolder[];
  /** The address search that opens a folder, so the open link keeps the page's other settings. */
  openSearch: (folderId: string) => string;
  onRename: (folder: ChatFolder) => void;
  onDelete: (folder: ChatFolder) => void;
}

export function FolderCards({ folders, openSearch, onRename, onDelete }: Readonly<FolderCardsProps>) {
  const { t } = useTranslation();
  return (
    <ul className="chat-foldercards">
      {folders.map((folder) => {
        const name = folder.name ?? '';
        return (
          <li key={folder.id} className="chat-foldercard">
            <Link to={{ search: openSearch(folder.id ?? '') }} className="chat-link chat-foldercard__open">
              {name}
            </Link>
            <span className="chat-foldercard__count">
              {t('chat.history.folderCount', { count: folder.conversationCount ?? 0 })}
            </span>
            <div className="chat-foldercard__actions">
              <button
                type="button"
                className="chat-button chat-button--quiet"
                aria-label={t('chat.history.renameFolder', { name })}
                onClick={() => onRename(folder)}
              >
                {t('chat.row.rename')}
              </button>
              <button
                type="button"
                className="chat-button chat-button--quiet"
                aria-label={t('chat.history.deleteFolder', { name })}
                onClick={() => onDelete(folder)}
              >
                {t('chat.row.delete')}
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

interface DeleteFolderDialogProps {
  /** The folder awaiting confirmation, or `null` while the dialog is closed. */
  name: string | null;
  deleting: boolean;
  failed: boolean;
  onKeep: () => void;
  onDelete: () => void;
}

export function DeleteFolderDialog({ name, ...rest }: Readonly<DeleteFolderDialogProps>) {
  return name === null ? null : <DeleteFolderForm name={name} {...rest} />;
}

/** Mounted only while open: a native modal that starts on "Keep" and gives focus back to what had it. */
function DeleteFolderForm({
  name,
  deleting,
  failed,
  onKeep,
  onDelete,
}: Readonly<Omit<DeleteFolderDialogProps, 'name'> & { name: string }>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

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

  return (
    <dialog
      ref={dialogRef}
      className="chat-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      aria-busy={deleting || undefined}
      onCancel={(event) => {
        event.preventDefault();
        if (!deleting) onKeep();
      }}
    >
      <h2 id={titleId}>{t('chat.history.deleteFolderDialog.title')}</h2>
      <p id={textId}>{t('chat.history.deleteFolderDialog.text', { name })}</p>
      {failed && (
        <p role="alert" className="form-error">
          {t('chat.history.deleteFolderDialog.failed')}
        </p>
      )}
      <div className="chat-dialog__actions">
        <button
          type="button"
          className="chat-button"
          aria-disabled={deleting || undefined}
          onClick={() => {
            if (!deleting) onKeep();
          }}
        >
          {t('chat.delete.keep')}
        </button>
        <button
          type="button"
          className="chat-button chat-button--danger"
          aria-disabled={deleting || undefined}
          aria-busy={deleting || undefined}
          onClick={() => {
            if (!deleting) onDelete();
          }}
        >
          {deleting ? t('chat.delete.deleting') : t('chat.delete.confirm')}
        </button>
      </div>
    </dialog>
  );
}
