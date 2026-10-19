import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AuthLink } from './AuthLink';
import { authCopy } from './authCopy';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
import { resendEmailSchema } from './registerSchema';
import {
  CONFIRMATION_LINK_INVALID,
  detailFrom,
  problemCodeFrom,
  useConfirmEmailMutation,
  useResendConfirmationMutation,
} from './useAuth';

interface ResendConfirmationProps {
  /** The address to send to; when omitted the member types it in. */
  email?: string;
  autoFocus?: boolean;
}

/** "Resend confirmation email" with its sending, sent and refused states. */
export function ResendConfirmation({ email, autoFocus = false }: ResendConfirmationProps) {
  const resend = useResendConfirmationMutation();
  const [typedEmail, setTypedEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const copy = authCopy.resend;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = resendEmailSchema.safeParse(email ?? typedEmail);
    if (!parsed.success) {
      setEmailError(copy.invalidEmail);
      return;
    }
    setEmailError(null);
    resend.mutate(parsed.data);
  };

  const refusal = resend.isError ? (detailFrom(resend) ?? copy.failed) : null;

  return (
    <form onSubmit={onSubmit} noValidate aria-label={copy.formLabel} className="auth-form">
      {email === undefined && (
        <div className="auth-field">
          <label htmlFor="resend-email" className="auth-label">
            {copy.emailLabel}
          </label>
          <div className="auth-control">
            <input
              id="resend-email"
              type="email"
              className="auth-input"
              autoComplete="email"
              value={typedEmail}
              onChange={(event) => setTypedEmail(event.target.value)}
              aria-invalid={emailError ? 'true' : 'false'}
              aria-describedby={emailError ? 'resend-email-error' : undefined}
            />
          </div>
          {emailError && (
            <p id="resend-email-error" role="alert" className="auth-error">
              {emailError}
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
        {resend.isPending ? copy.pending : copy.submit}
      </button>
      {resend.isSuccess && (
        <p role="status" className="auth-status">
          {copy.sent}
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

/** Opened from the emailed link: spends `?token=` once, then lands on the home page signed in. */
export function ConfirmEmailPage() {
  const copy = authCopy.confirmEmail;
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
  const otherFailure =
    !linkInvalid && confirm.isError ? (detailFrom(confirm) ?? copy.failedFallback) : null;
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
        {copy.title}
      </h1>

      {linkInvalid && (
        <>
          <div role="alert" className="auth-alert">
            <p>{copy.linkInvalid}</p>
            <p>{copy.linkInvalidHint}</p>
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
            onClick={() => mutate(token, { onSuccess: () => navigate('/', { replace: true }) })}
          >
            {copy.retry}
          </button>
        </>
      )}

      {!linkInvalid && !otherFailure && (
        <p role="status" className="auth-status">
          {copy.confirming}
        </p>
      )}

      <p className="auth-footnote">
        <AuthLink to="/login">{copy.backToSignIn}</AuthLink>
      </p>
    </>
  );
}
