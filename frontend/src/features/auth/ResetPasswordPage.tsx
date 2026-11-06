import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { PasswordResetGate, RequestResetForm } from './RequestResetForm';
import { resetPasswordSchema, type ResetPasswordFormValues } from './registerSchema';
import {
  RESET_LINK_INVALID,
  failureMessage,
  fieldErrorsFrom,
  problemCodeFrom,
  resetWaitMessage,
  useResetPasswordMutation,
} from './useAuth';

const FIELDS: readonly { name: keyof ResetPasswordFormValues; labelKey: string }[] = [
  { name: 'password', labelKey: 'auth.reset.password.passwordLabel' },
  { name: 'confirmPassword', labelKey: 'auth.reset.password.confirmPasswordLabel' },
];

function tokenFrom(hash: string): string {
  return new URLSearchParams(hash.replace(/^#/, '')).get('token') ?? '';
}

/**
 * Opened from the emailed `/reset-password#token=…` link. The token rides in the fragment so no
 * server or access log sees it, and the page drops it from the address bar once read.
 */
export function ResetPasswordPage() {
  const { t } = useTranslation();
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
        <p className="eyebrow">{t('auth.reset.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.reset.password.title')}</h1>

        <PasswordResetGate>
          {linkInvalid ? (
            <>
              <p ref={linkInvalidMessage} tabIndex={-1} role="alert" className="form-error">
                {t('auth.reset.password.linkInvalid')}
              </p>
              <p className="auth-subtitle">{t('auth.reset.password.newLinkIntro')}</p>
              <RequestResetForm action={t('auth.reset.password.newLinkSubmit')} />
            </>
          ) : (
            <ResetPasswordForm key={token} token={token} onLinkInvalid={onLinkInvalid} />
          )}
        </PasswordResetGate>

        <p className="auth-footer">
          <Link to="/login">{t('auth.reset.backToSignIn')}</Link>
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
  const { t } = useTranslation();
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
          navigate('/login', {
            replace: true,
            state: { notice: t('auth.reset.password.changedNotice'), email },
          }),
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
      ? (resetWaitMessage(reset.error, t) ?? failureMessage(reset, t, 'auth.reset.password.failed'))
      : null;

  return (
    <>
      <p className="auth-subtitle">{t('auth.reset.password.intro')}</p>

      <form
        onSubmit={onSubmit}
        noValidate
        aria-label={t('auth.reset.password.formLabel')}
        className="auth-form"
      >
        {FIELDS.map(({ name, labelKey }, index) => {
          const id = `reset-password-${name}`;
          const error = errors[name];
          // Schema messages are locale keys; a server refusal arrives already worded and is shown as is.
          const message =
            error?.message && (error.type === 'server' ? error.message : t(error.message));
          return (
            <div className="field" key={name} style={{ '--i': index } as React.CSSProperties}>
              <label htmlFor={id}>{t(labelKey)}</label>
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
          {reset.isPending ? t('auth.reset.password.saving') : t('auth.reset.password.submit')}
        </button>
      </form>
    </>
  );
}
