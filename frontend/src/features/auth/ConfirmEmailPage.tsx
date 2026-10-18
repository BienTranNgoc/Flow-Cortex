import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
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
  email?: string;
}

/** "Resend confirmation email" with its sending, sent and refused states. */
export function ResendConfirmation({ email }: ResendConfirmationProps) {
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
      aria-label={t('auth.confirm.resend.formLabel')}
      className="auth-form"
    >
      {email === undefined && (
        <div className="field">
          <label htmlFor="resend-email">{t('auth.confirm.resend.emailLabel')}</label>
          <input
            id="resend-email"
            type="email"
            autoComplete="email"
            value={typedEmail}
            onChange={(event) => setTypedEmail(event.target.value)}
            aria-invalid={emailErrorKey ? 'true' : 'false'}
            aria-describedby={emailErrorKey ? 'resend-email-error' : undefined}
          />
          {emailErrorKey && (
            <p id="resend-email-error" role="alert">
              {t(emailErrorKey)}
            </p>
          )}
        </div>
      )}
      <button type="submit" disabled={resend.isPending}>
        {resend.isPending ? t('auth.confirm.resend.sending') : t('auth.confirm.resend.submit')}
      </button>
      {resend.isSuccess && (
        <p role="status" className="auth-subtitle">
          {t('auth.confirm.resend.sent')}
        </p>
      )}
      {refusal && (
        <p role="alert" className="form-error">
          {refusal}
        </p>
      )}
    </form>
  );
}

/** Opened from the emailed link: spends `?token=` once, then lands on the home page signed in. */
export function ConfirmEmailPage() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();
  const confirm = useConfirmEmailMutation();
  const { mutate } = confirm;
  // A link spends on first use, so StrictMode's second effect run must not send it again.
  const sent = useRef(false);

  useEffect(() => {
    if (!token || sent.current) {
      return;
    }
    sent.current = true;
    mutate(token, { onSuccess: () => navigate('/', { replace: true }) });
  }, [token, mutate, navigate]);

  const linkInvalid =
    !token || (confirm.isError && problemCodeFrom(confirm.error) === CONFIRMATION_LINK_INVALID);
  const otherFailure = linkInvalid ? undefined : failureMessage(confirm, t, 'auth.confirm.failed');

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">{t('auth.confirm.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.confirm.title')}</h1>

        {linkInvalid && (
          <>
            <p role="alert" className="form-error">
              {t('auth.confirm.linkInvalid')}
            </p>
            <p className="auth-subtitle">{t('auth.confirm.enterEmail')}</p>
            <ResendConfirmation />
          </>
        )}

        {otherFailure && (
          <>
            <p role="alert" className="form-error">
              {otherFailure}
            </p>
            <button
              type="button"
              disabled={confirm.isPending}
              onClick={() => mutate(token, { onSuccess: () => navigate('/', { replace: true }) })}
            >
              {t('auth.confirm.tryAgain')}
            </button>
          </>
        )}

        {!linkInvalid && !otherFailure && (
          <p role="status" className="auth-subtitle">
            {t('auth.confirm.confirming')}
          </p>
        )}

        <p className="auth-footer">
          <Link to="/login">{t('auth.confirm.backToSignIn')}</Link>
        </p>
      </section>
    </div>
  );
}
