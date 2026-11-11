import { useEffect, useId, useRef, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { accountsOfRoleHref } from './roleDisplay';

interface DeleteRoleDialogProps {
  readonly code: string;
  readonly name: string;
  readonly pending: boolean;
  /** The localized problem text of the last failed attempt, shown inside the dialog. */
  readonly error: string | undefined;
  /** The refusal came from accounts still holding the role: offer the filtered accounts list. */
  readonly held: boolean;
  readonly canSeeAccounts: boolean;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

/** Modal that confirms deleting a custom role; a refusal stays inside it so the reason is read in place. */
export function DeleteRoleDialog({
  code,
  name,
  pending,
  error,
  held,
  canSeeAccounts,
  onConfirm,
  onClose,
}: Readonly<DeleteRoleDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!pending) onConfirm();
  };

  return (
    <dialog
      ref={dialogRef}
      className="roles-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
    >
      <form className="roles-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t('roles.detail.delete.title', { name })}</h2>
        <p>{t('roles.detail.delete.description')}</p>
        {error && (
          <p className="cortex-admin-form-error" role="alert">
            {error}
          </p>
        )}
        {error && held && canSeeAccounts && (
          <Link className="cortex-admin-link" to={accountsOfRoleHref(code)}>
            {t('roles.detail.delete.heldLink')}
          </Link>
        )}
        <div className="roles-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('roles.detail.delete.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--danger"
            disabled={pending}
          >
            {pending ? t('roles.detail.delete.working') : t('roles.detail.delete.confirm')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
