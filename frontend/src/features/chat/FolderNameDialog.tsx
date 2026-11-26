import { ApiError, type ChatFolder } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { chatProblem, chatProblemCode } from './chatProblem';
import { useCreateFolder, useRenameFolder } from './history/useChatHistory';

/** Mirrors the cap `FolderName` applies on the server, which is the source of truth. */
const MAX_FOLDER_NAME = 60;

export type FolderNameTarget = { kind: 'create' } | { kind: 'rename'; id: string; name: string };

interface FolderNameDialogProps {
  /** What the dialog does, or `null` while it is closed. */
  target: FolderNameTarget | null;
  onClose: () => void;
  onSaved?: ((folder: ChatFolder) => void) | undefined;
}

export function FolderNameDialog({ target, onClose, onSaved }: Readonly<FolderNameDialogProps>) {
  return target ? <NameForm target={target} onClose={onClose} onSaved={onSaved} /> : null;
}

/** A name is 1 to 60 characters after trimming, counted in code points like the server does. */
function checkName(raw: string) {
  const name = raw.trim();
  const length = [...name].length;
  return { name, length, ok: length > 0 && length <= MAX_FOLDER_NAME, empty: length === 0 };
}

/** The limit refusal carries the server's own localized sentence, which names the number. */
function problemText(error: unknown, t: TFunction): string {
  switch (chatProblemCode(error)) {
    case 'chat-folder-limit':
      return (error instanceof ApiError ? error.problem?.detail : undefined) ?? t('chat.folders.limit');
    case 'chat-folder-not-found':
      return t('chat.folders.gone');
    default:
      return chatProblem(error, t).message;
  }
}

/** Mounted only while open: it opens as a native modal and gives focus back to what had it. */
function NameForm({
  target,
  onClose,
  onSaved,
}: Readonly<
  Omit<FolderNameDialogProps, 'target'> & { target: FolderNameTarget }
>) {
  const { t } = useTranslation();
  const titleId = useId();
  const inputId = useId();
  const noteId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const create = useCreateFolder();
  const rename = useRenameFolder();
  const [draft, setDraft] = useState(target.kind === 'rename' ? target.name : '');
  const [touched, setTouched] = useState(false);
  const check = checkName(draft);
  const saving = create.isPending || rename.isPending;
  const error = target.kind === 'create' ? create.error : rename.error;

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

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (!check.ok || saving) return;
    if (target.kind === 'rename' && check.name === target.name) {
      onClose();
      return;
    }
    const done = (folder: ChatFolder) => {
      onSaved?.(folder);
      onClose();
    };
    if (target.kind === 'create') create.mutate(check.name, { onSuccess: done });
    else rename.mutate({ id: target.id, name: check.name }, { onSuccess: done });
  };

  const problem = error ? problemText(error, t) : null;
  const invalid = (touched || check.length > MAX_FOLDER_NAME) && !check.ok;

  return (
    <dialog
      ref={dialogRef}
      className="chat-dialog"
      aria-labelledby={titleId}
      aria-busy={saving || undefined}
      onCancel={(event) => {
        event.preventDefault();
        if (!saving) onClose();
      }}
    >
      <form onSubmit={submit} noValidate>
        <h2 id={titleId}>{target.kind === 'create' ? t('chat.folders.createTitle') : t('chat.folders.renameTitle')}</h2>
        <label htmlFor={inputId} className="chat-field__label">
          {t('chat.folders.nameLabel')}
        </label>
        <input
          id={inputId}
          className="chat-row__input chat-field__input"
          value={draft}
          autoFocus
          autoComplete="off"
          aria-describedby={noteId}
          aria-invalid={invalid || undefined}
          onChange={(event) => setDraft(event.target.value)}
        />
        <p id={noteId} className="chat-row__note">
          <span className={check.length > MAX_FOLDER_NAME ? 'chat-counter--over' : undefined}>
            {t('chat.folders.counter', { length: check.length, max: MAX_FOLDER_NAME })}
          </span>
          {invalid && (
            <span role="alert">
              {' '}
              {check.empty
                ? t('chat.folders.nameEmpty')
                : t('chat.folders.nameTooLong', { max: MAX_FOLDER_NAME })}
            </span>
          )}
        </p>
        {problem && (
          <p role="alert" className="form-error">
            {problem}
          </p>
        )}
        <div className="chat-dialog__actions">
          <button
            type="button"
            className="chat-button"
            aria-disabled={saving || undefined}
            onClick={() => {
              if (!saving) onClose();
            }}
          >
            {t('chat.folders.cancel')}
          </button>
          <button
            type="submit"
            className="chat-button chat-button--primary"
            aria-disabled={saving || !check.ok || undefined}
            aria-busy={saving || undefined}
          >
            {saving
              ? t('chat.folders.saving')
              : target.kind === 'create'
                ? t('chat.folders.create')
                : t('chat.folders.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
