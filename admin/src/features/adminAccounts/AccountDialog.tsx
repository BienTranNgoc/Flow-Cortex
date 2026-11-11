import type { AdminAccountResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { roleLabel } from '../users/enumLabels';

export type AccountAction = 'role' | 'deactivate' | 'activate';

interface AccountDialogProps {
  readonly account: AdminAccountResponse;
  readonly action: AccountAction;
  readonly roles: readonly string[];
  readonly pending: boolean;
  /** The localized problem text of the last failed attempt, shown inside the dialog. */
  readonly error: string | undefined;
  readonly onConfirm: (role: string) => void;
  readonly onClose: () => void;
}

/** Modal that confirms a role change, a deactivation or a reactivation; it stays open on a failure so the reason is read in place. */
export function AccountDialog({
  account,
  action,
  roles,
  pending,
  error,
  onConfirm,
  onClose,
}: AccountDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const roleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [role, setRole] = useState(account.role);
  const unchanged = action === 'role' && role === account.role;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!unchanged && !pending) onConfirm(role);
  };

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog cortex-admin-accounts-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
    >
      <form className="cortex-admin-accounts-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t(`adminAccounts.dialog.${action}.title`, { name: account.name })}</h2>
        <p>
          {t(`adminAccounts.dialog.${action}.description`, {
            name: account.name,
            email: account.email,
          })}
        </p>
        {action === 'role' && (
          <div className="cortex-admin-field">
            <label htmlFor={roleId}>{t('adminAccounts.dialog.role.label')}</label>
            <select id={roleId} value={role} onChange={(event) => setRole(event.target.value)}>
              {roles.map((code) => (
                <option key={code} value={code}>
                  {roleLabel(t, code)}
                </option>
              ))}
            </select>
          </div>
        )}
        {error && (
          <p className="cortex-admin-form-error" role="alert">
            {error}
          </p>
        )}
        <div className="cortex-admin-accounts-dialog__actions">
          <button type="button" className="cortex-admin-btn" onClick={() => dialogRef.current?.close()}>
            {t('adminAccounts.dialog.cancel')}
          </button>
          <button
            type="submit"
            className={
              action === 'deactivate'
                ? 'cortex-admin-btn cortex-admin-btn--danger'
                : 'cortex-admin-btn cortex-admin-btn--primary'
            }
            disabled={pending || unchanged}
          >
            {pending ? t('adminAccounts.dialog.working') : t(`adminAccounts.dialog.${action}.confirm`)}
          </button>
        </div>
      </form>
    </dialog>
  );
}
