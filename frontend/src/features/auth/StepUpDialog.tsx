import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, authHeaders, stepUp, unwrap } from '@flow-cortex/api-client';
import type { StepUpRequestPurpose, StepUpResponse } from '@flow-cortex/api-client';
import { stepUpFailure, type StepUpFailure } from './stepUpFailure';
import './StepUpDialog.css';

interface StepUpDialogProps {
  purpose: StepUpRequestPurpose;
  open: boolean;
  /** Receives the one-use proof once the password was accepted; the caller closes the dialog. */
  onProof: (proof: string) => void;
  onCancel: () => void;
}

/**
 * Asks the member for their password again before a sensitive action, as a native modal dialog:
 * focus starts on the password field, Escape cancels, and focus goes back to whatever opened it.
 */
export function StepUpDialog({ purpose, open, onProof, onCancel }: Readonly<StepUpDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const errorId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  // Bumped on every open and cancel, so an answer that arrives after either is dropped.
  const attemptRef = useRef(0);
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<StepUpFailure | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        attemptRef.current += 1;
        setPassword('');
        setPending(false);
        setFailure(null);
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        inputRef.current?.focus();
      }
      return;
    }
    attemptRef.current += 1;
    if (dialog.open) dialog.close();
    setPassword('');
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  // The account has no password to confirm, so retrying cannot succeed.
  const passwordRequired = failure?.kind === 'passwordRequired';

  const cancel = () => {
    attemptRef.current += 1;
    onCancel();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (pending || password === '' || passwordRequired) return;
    attemptRef.current += 1;
    const attempt = attemptRef.current;
    setPending(true);
    setFailure(null);
    try {
      const request = stepUp({ password, purpose }, authHeaders());
      const response = await request;
      // The generated client hands back the headers only here; `unwrap` keeps the 401 handling.
      const retryAfter = response.headers.get('Retry-After');
      try {
        const granted = (await unwrap(Promise.resolve(response))) as StepUpResponse;
        if (attempt !== attemptRef.current) return;
        setPassword('');
        setPending(false);
        onProof(granted.stepUpProof);
      } catch (error) {
        if (attempt !== attemptRef.current) return;
        const refused =
          error instanceof ApiError
            ? stepUpFailure(error.status, error.problem, retryAfter)
            : ({ kind: 'unknown' } as const);
        setFailure(refused);
        setPending(false);
        if (refused.kind === 'invalidPassword') {
          setPassword('');
          inputRef.current?.focus();
        }
      }
    } catch {
      if (attempt !== attemptRef.current) return;
      setFailure({ kind: 'unknown' });
      setPending(false);
    }
  };

  return (
    <dialog
      ref={dialogRef}
      className="step-up-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        cancel();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (open) cancel();
      }}
    >
      <form onSubmit={(event) => void submit(event)} noValidate>
        <h2 id={titleId}>{t('auth.stepUp.title')}</h2>
        <p id={textId}>{t(`auth.stepUp.purpose.${purpose}`)}</p>
        <div className="field">
          <label htmlFor={`${titleId}-password`}>{t('auth.stepUp.passwordLabel')}</label>
          <input
            id={`${titleId}-password`}
            ref={inputRef}
            type="password"
            autoComplete="current-password"
            maxLength={72}
            value={password}
            disabled={passwordRequired}
            onChange={(event) => setPassword(event.target.value)}
            aria-invalid={failure?.kind === 'invalidPassword' || undefined}
            aria-describedby={failure ? errorId : undefined}
          />
        </div>
        {failure && (
          <p id={errorId} role="alert" className="form-error">
            {failure.kind === 'invalidPassword' && t('auth.stepUp.invalidPassword')}
            {failure.kind === 'passwordRequired' && t('auth.stepUp.passwordRequired')}
            {failure.kind === 'rateLimited' &&
              t('auth.stepUp.rateLimited', { seconds: failure.seconds })}
            {failure.kind === 'unknown' && t('auth.stepUp.failed')}
          </p>
        )}
        <div className="step-up-actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={cancel}>
            {t('auth.stepUp.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={pending || password === '' || passwordRequired}
            aria-busy={pending}
          >
            {pending ? t('auth.stepUp.confirming') : t('auth.stepUp.confirm')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
