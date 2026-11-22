import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { StepUpRequestPurpose } from '@flow-cortex/api-client';
import { StepUpDialog } from './StepUpDialog';
import {
  deletionFailure,
  useDeletionEligibility,
  useRequestAccountDeletion,
  type BlockReason,
  type DeletionFailure,
} from './useAccountDeletion';
import './AccountDeletion.css';

interface AccountDeletionDialogProps {
  open: boolean;
  gracePeriodDays: number;
  onClose: () => void;
}

function failureKey(failure: DeletionFailure): string {
  switch (failure.kind) {
    case 'staffAccount':
      return 'auth.accountDeletion.errors.staffAccount';
    case 'stepUpRequired':
      return 'auth.accountDeletion.errors.stepUpRequired';
    case 'unavailable':
      return 'auth.accountDeletion.errors.unavailable';
    default:
      return 'auth.accountDeletion.errors.failed';
  }
}

function viewOf(checking: boolean, checkFailed: boolean, blocked: boolean) {
  if (checking) return 'checking';
  if (checkFailed) return 'checkFailed';
  return blocked ? 'blocked' : 'confirm';
}

/**
 * Steps of a deletion request as a native modal dialog: eligibility is read each time it opens;
 * a blocked account sees the reasons and can only close; an eligible one confirms, proves its
 * password in `StepUpDialog` and submits. A 202 leads to the pending screen.
 */
export function AccountDeletionDialog({
  open,
  gracePeriodDays,
  onClose,
}: Readonly<AccountDeletionDialogProps>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const eligibility = useDeletionEligibility(open);
  const request = useRequestAccountDeletion();
  const { reset: resetRequest } = request;
  const [askingPassword, setAskingPassword] = useState(false);
  const [failure, setFailure] = useState<DeletionFailure | null>(null);
  const submitting = request.isPending;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        resetRequest();
        setAskingPassword(false);
        setFailure(null);
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open, resetRequest]);

  const close = () => {
    if (!submitting) onClose();
  };

  const onProof = (proof: string) => {
    setAskingPassword(false);
    setFailure(null);
    request.mutate(proof, {
      onSuccess: () => {
        onClose();
        navigate('/account-deletion', { replace: true });
      },
      onError: (error) => setFailure(deletionFailure(error)),
    });
  };

  let reasons: BlockReason[] | null = null;
  if (failure?.kind === 'blocked') reasons = failure.reasons;
  else if (eligibility.data && !eligibility.data.eligible) reasons = eligibility.data.reasons;
  // Only the first answer of an opening is awaited; a background refetch keeps the current view.
  const checking =
    failure?.kind !== 'blocked' &&
    !eligibility.data &&
    (eligibility.isPending || eligibility.isFetching);
  const checkFailed =
    failure?.kind !== 'blocked' && !checking && !eligibility.data && eligibility.isError;
  const view = viewOf(checking, checkFailed, reasons !== null);

  // A view swap unmounts the focused button; hand focus to the new view instead of the page.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!open || !dialog?.open || askingPassword) return;
    if (!dialog.contains(document.activeElement)) {
      dialog.querySelector<HTMLButtonElement>('button:not([disabled])')?.focus();
    }
  }, [view, open, askingPassword]);

  let body;
  if (checking) {
    body = (
      <>
        <p role="status">{t('auth.accountDeletion.dialog.checking')}</p>
        <div className="account-deletion-dialog__actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={close}>
            {t('auth.accountDeletion.dialog.close')}
          </button>
        </div>
      </>
    );
  } else if (checkFailed) {
    body = (
      <>
        <p role="alert" className="form-error">
          {t('auth.accountDeletion.dialog.checkFailed')}
        </p>
        <div className="account-deletion-dialog__actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={close}>
            {t('auth.accountDeletion.dialog.close')}
          </button>
          <button
            type="button"
            className="cortex-btn cortex-btn--primary"
            onClick={() => void eligibility.refetch()}
          >
            {t('auth.accountDeletion.dialog.retry')}
          </button>
        </div>
      </>
    );
  } else if (reasons) {
    body = (
      <>
        <p>{t('auth.accountDeletion.dialog.blockedIntro')}</p>
        <ul className="account-deletion-dialog__reasons">
          {reasons.map((reason) => (
            <li key={reason.key}>{reason.message}</li>
          ))}
        </ul>
        <div className="account-deletion-dialog__actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={close}>
            {t('auth.accountDeletion.dialog.close')}
          </button>
        </div>
      </>
    );
  } else {
    body = (
      <>
        <p>{t('auth.accountDeletion.dialog.confirm', { days: gracePeriodDays })}</p>
        <p>{t('auth.accountDeletion.dialog.signOutNotice')}</p>
        {failure && (
          <p role="alert" className="form-error">
            {t(failureKey(failure))}
          </p>
        )}
        <div className="account-deletion-dialog__actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={close}
            disabled={submitting}
          >
            {t('auth.accountDeletion.dialog.keep')}
          </button>
          <button
            type="button"
            className="cortex-btn cortex-btn--danger"
            onClick={() => setAskingPassword(true)}
            disabled={submitting || failure?.kind === 'staffAccount'}
            aria-busy={submitting}
          >
            {submitting
              ? t('auth.accountDeletion.dialog.submitting')
              : t('auth.accountDeletion.dialog.continue')}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <dialog
        ref={dialogRef}
        className="account-deletion-dialog"
        aria-labelledby={titleId}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
        // A browser may close the dialog on a repeated Escape despite `preventDefault`. A sent
        // request cannot be dismissed, so the dialog is shown again to keep the DOM and `open` in
        // step and to let the outcome be seen.
        onClose={(event) => {
          if (!open) return;
          if (!submitting) onClose();
          else if (!event.currentTarget.open) event.currentTarget.showModal();
        }}
      >
        <h2 id={titleId}>{t('auth.accountDeletion.dialog.title')}</h2>
        {body}
      </dialog>
      <StepUpDialog
        purpose={StepUpRequestPurpose.ACCOUNT_DELETION}
        open={askingPassword}
        onProof={onProof}
        onCancel={() => setAskingPassword(false)}
      />
    </>
  );
}
