import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AuthLink } from './AuthLink';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
import { resendEmailSchema } from './registerSchema';
import {
  CONFIRMATION_LINK_INVALID,
  failureMessage,
  problemCodeFrom,
  useConfirmEmailMutation,
  useResendConfirmationMutation,
} from './useAuth';

const EMAIL_INVALID_KEY = 'auth.validation.emailInvalid';

interface ResendConfirmationProps {
  /** The address to send to; when omitted the member types it in. */
  email?: string | undefined;
  /** Translated button and form name; the same request mails a set-password link when needed. */
  action?: string;
  /** Translated, shown once sent; it never says whether the address has an account. */
  sentMessage?: string;
  autoFocus?: boolean;
}

/** "Resend confirmation email" with its sending, sent and refused states. */
export function ResendConfirmation({
  email,
  action,
  sentMessage,
  autoFocus = false,
}: ResendConfirmationProps) {
  const { t } = useTranslation();
  const resend = useResendConfirmationMutation();
  const [typedEmail, setTypedEmail] = useState('');
  const [emailErrorKey, setEmailErrorKey] = useState<string | null>(null);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = resendEmailSchema.safeParse(email ?? typedEmail);
    if (!parsed.success) {
      setEmailErrorKey(parsed.error.issues[0]?.message ?? EMAIL_INVALID_KEY);
      return;
    }
    setEmailErrorKey(null);
    resend.mutate(parsed.data);
  };

  const refusal = failureMessage(resend, t, 'auth.confirm.resend.failed');

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-label={action ?? t('auth.confirm.resend.formLabel')}
      className="auth-form"
    >
      {email === undefined && (
        <div className="auth-field">
          <label htmlFor="resend-email" className="auth-label">
            {t('auth.confirm.resend.emailLabel')}
          </label>
          <div className="auth-control">
            <input
              id="resend-email"
              type="email"
              className="auth-input"
              autoComplete="email"
              value={typedEmail}
              onChange={(event) => setTypedEmail(event.target.value)}
              aria-invalid={emailErrorKey ? 'true' : 'false'}
              aria-describedby={emailErrorKey ? 'resend-email-error' : undefined}
            />
          </div>
          {emailErrorKey && (
            <p id="resend-email-error" role="alert" className="auth-error">
              {t(emailErrorKey)}
            </p>
          )}
        </div>
      )}
      <button
        type="submit"
        className="auth-btn auth-btn--secondary"
        disabled={resend.isPending}
        aria-busy={resend.isPending}
        autoFocus={autoFocus}
      >
        {resend.isPending
          ? t('auth.confirm.resend.sending')
          : (action ?? t('auth.confirm.resend.submit'))}
      </button>
      {resend.isSuccess && (
        <p role="status" className="auth-status">
          {sentMessage ?? t('auth.confirm.resend.sent')}
        </p>
      )}
      {refusal && (
        <p role="alert" className="auth-alert">
          {refusal}
        </p>
      )}
    </form>
  );
}

/**
 * Opened from the emailed link: spends `?token=` once and signs the member in, then asks for a
 * password when the account has none (confirming drops the one chosen at sign-up), else goes home.
 */
export function ConfirmEmailPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();
  const confirm = useConfirmEmailMutation();
  const { mutate } = confirm;
  // A link spends on first use, so StrictMode's second effect run must not send it again.
  const sent = useRef(false);

  const spend = useCallback(
    () =>
      mutate(token, {
        onSuccess: ({ passwordSetupRequired, passwordSetupToken }) =>
          passwordSetupRequired
            ? navigate('/set-password', { replace: true, state: { passwordSetupToken } })
            : navigate('/', { replace: true }),
      }),
    [token, mutate, navigate],
  );

  useEffect(() => {
    if (!token || sent.current) {
      return;
    }
    sent.current = true;
    spend();
  }, [token, spend]);

  const linkInvalid =
    !token || (confirm.isError && problemCodeFrom(confirm.error) === CONFIRMATION_LINK_INVALID);
  const otherFailure = linkInvalid ? undefined : failureMessage(confirm, t, 'auth.confirm.failed');
  useAuthPose(
    nodiPeekState({
      focused: null,
      pending: confirm.isPending,
      failed: linkInvalid || confirm.isError,
      succeeded: confirm.isSuccess,
    }),
  );

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.confirm.title')}
      </h1>

      {linkInvalid && (
        <>
          <div role="alert" className="auth-alert">
            <p>{t('auth.confirm.linkInvalid')}</p>
            <p>{t('auth.confirm.enterEmail')}</p>
          </div>
          <ResendConfirmation />
        </>
      )}

      {otherFailure && (
        <>
          <p role="alert" className="auth-alert">
            {otherFailure}
          </p>
          <button
            type="button"
            className="auth-btn auth-btn--secondary"
            disabled={confirm.isPending}
            aria-busy={confirm.isPending}
            onClick={spend}
          >
            {t('auth.confirm.tryAgain')}
          </button>
        </>
      )}

      {!linkInvalid && !otherFailure && (
        <p role="status" className="auth-status">
          {t('auth.confirm.confirming')}
        </p>
      )}

      <p className="auth-footnote">
        <AuthLink to="/login">{t('auth.confirm.backToSignIn')}</AuthLink>
      </p>
    </>
  );
}
