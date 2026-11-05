import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { PasswordResetGate, RequestResetForm } from './RequestResetForm';
import { resetPasswordSchema, type ResetPasswordFormValues } from './registerSchema';
import {
  RESET_LINK_INVALID,
  detailFrom,
  fieldErrorsFrom,
  problemCodeFrom,
  resetWaitMessage,
  useResetPasswordMutation,
} from './useAuth';

const LINK_INVALID_MESSAGE = 'This reset link is no longer valid';

const PASSWORD_CHANGED_NOTICE =
  'Password updated. Sign in with your new password. We signed you out on every device. A device that was already open may stay signed in for up to 15 minutes.';

const FIELDS: readonly { name: keyof ResetPasswordFormValues; label: string }[] = [
  { name: 'password', label: 'New password' },
  { name: 'confirmPassword', label: 'Confirm password' },
];

function tokenFrom(hash: string): string {
  return new URLSearchParams(hash.replace(/^#/, '')).get('token') ?? '';
}

/**
 * Opened from the emailed `/reset-password#token=…` link. The token rides in the fragment so no
 * server or access log sees it, and the page drops it from the address bar once read.
 */
export function ResetPasswordPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [token, setToken] = useState(() => tokenFrom(location.hash));
  const [linkInvalid, setLinkInvalid] = useState(token === '');
  const linkInvalidMessage = useRef<HTMLParagraphElement>(null);
  const focusLinkInvalid = useRef(false);

  // A newer link opened in this tab changes only the fragment, so the page stays mounted.
  useEffect(() => {
    if (location.hash) {
      const next = tokenFrom(location.hash);
      if (next) {
        setToken(next);
        setLinkInvalid(false);
      }
      navigate('/reset-password', { replace: true });
    }
  }, [location.hash, navigate]);

  // Unmounting the form on a refused link would otherwise drop focus to <body>.
  useEffect(() => {
    if (linkInvalid && focusLinkInvalid.current) {
      focusLinkInvalid.current = false;
      linkInvalidMessage.current?.focus();
    }
  }, [linkInvalid]);

  const onLinkInvalid = () => {
    focusLinkInvalid.current = true;
    setLinkInvalid(true);
  };

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">CREATOR ACCESS · RESET PASSWORD</p>
        <h1 className="auth-card__title">Choose a new password</h1>

        <PasswordResetGate>
          {linkInvalid ? (
            <>
              <p ref={linkInvalidMessage} tabIndex={-1} role="alert" className="form-error">
                {LINK_INVALID_MESSAGE}
              </p>
              <p className="auth-subtitle">Enter your email and we will send you a new link.</p>
              <RequestResetForm action="Send a new link" />
            </>
          ) : (
            <ResetPasswordForm key={token} token={token} onLinkInvalid={onLinkInvalid} />
          )}
        </PasswordResetGate>

        <p className="auth-footer">
          <Link to="/login">Back to sign in</Link>
        </p>
      </section>
    </div>
  );
}

interface ResetPasswordFormProps {
  token: string;
  onLinkInvalid: () => void;
}

function ResetPasswordForm({ token, onLinkInvalid }: ResetPasswordFormProps) {
  const navigate = useNavigate();
  const reset = useResetPasswordMutation();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { password: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit(({ password }) => {
    reset.mutate(
      { token, password },
      {
        onSuccess: (email) =>
          navigate('/login', { replace: true, state: { notice: PASSWORD_CHANGED_NOTICE, email } }),
        onError: (error) => {
          const refused = fieldErrorsFrom(error);
          if (problemCodeFrom(error) === RESET_LINK_INVALID || refused.token) {
            onLinkInvalid();
            return;
          }
          if (refused.password) {
            setError(
              'password',
              { type: 'server', message: refused.password },
              { shouldFocus: true },
            );
          }
        },
      },
    );
  });

  const serverError =
    reset.isError && !fieldErrorsFrom(reset.error).password
      ? (resetWaitMessage(reset.error) ??
        detailFrom(reset) ??
        'Could not set your password. Please try again.')
      : null;

  return (
    <>
      <p className="auth-subtitle">Choose the password you will sign in with from now on.</p>

      <form onSubmit={onSubmit} noValidate aria-label="Set a new password" className="auth-form">
        {FIELDS.map(({ name, label }, index) => {
          const id = `reset-password-${name}`;
          const message = errors[name]?.message;
          return (
            <div className="field" key={name} style={{ '--i': index } as React.CSSProperties}>
              <label htmlFor={id}>{label}</label>
              <input
                id={id}
                type="password"
                autoComplete="new-password"
                aria-invalid={message ? 'true' : 'false'}
                aria-describedby={message ? `${id}-error` : undefined}
                {...register(name)}
              />
              {message && (
                <p id={`${id}-error`} role="alert">
                  {message}
                </p>
              )}
            </div>
          );
        })}
        {serverError && (
          <p role="alert" className="form-error">
            {serverError}
          </p>
        )}
        <button className="primary-button" type="submit" disabled={reset.isPending}>
          {reset.isPending ? 'Saving…' : 'Set new password'}
        </button>
      </form>
    </>
  );
}
