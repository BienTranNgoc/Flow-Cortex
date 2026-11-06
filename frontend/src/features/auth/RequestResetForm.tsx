import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { useFeaturesQuery } from '../../app/useFeatures';
import { resendEmailSchema } from './registerSchema';
import {
  failureMessage,
  fieldErrorsFrom,
  resetWaitMessage,
  useForgotPasswordMutation,
} from './useAuth';

function refusalMessage(forgot: { readonly error: unknown }, t: TFunction): string {
  return (
    resetWaitMessage(forgot.error, t) ??
    failureMessage(forgot, t, 'auth.reset.request.failed') ??
    ''
  );
}

interface RequestResetFormProps {
  action: string;
}

/** Asks for a reset link; the answer repeats the typed address and never says whether it has an account. */
export function RequestResetForm({ action }: RequestResetFormProps) {
  const { t } = useTranslation();
  const forgot = useForgotPasswordMutation();
  const [typedEmail, setTypedEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const emailInput = useRef<HTMLInputElement>(null);

  const refuseEmail = (message: string) => {
    setEmailError(message);
    emailInput.current?.focus();
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = resendEmailSchema.safeParse(typedEmail);
    if (!parsed.success) {
      forgot.reset();
      refuseEmail(t(parsed.error.issues[0]?.message ?? 'auth.validation.emailInvalid'));
      return;
    }
    setEmailError(null);
    forgot.mutate(parsed.data, {
      onError: (error) => {
        const message = fieldErrorsFrom(error).email;
        if (message) refuseEmail(message);
      },
    });
  };

  const refusal =
    forgot.isError && !fieldErrorsFrom(forgot.error).email ? refusalMessage(forgot, t) : null;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-label={t('auth.reset.request.formLabel')}
      className="auth-form"
    >
      <div className="auth-field">
        <label htmlFor="reset-request-email" className="auth-label">
          {t('auth.reset.request.emailLabel')}
        </label>
        <div className="auth-control">
          <input
            id="reset-request-email"
            ref={emailInput}
            type="email"
            className="auth-input"
            autoComplete="email"
            value={typedEmail}
            onChange={(event) => setTypedEmail(event.target.value)}
            aria-invalid={emailError ? 'true' : 'false'}
            aria-describedby={emailError ? 'reset-request-email-error' : undefined}
          />
        </div>
        {emailError && (
          <p id="reset-request-email-error" role="alert" className="auth-error">
            {emailError}
          </p>
        )}
      </div>
      <button
        className="auth-btn auth-btn--primary"
        type="submit"
        disabled={forgot.isPending}
        aria-busy={forgot.isPending}
      >
        {forgot.isPending && <span className="auth-spinner" aria-hidden="true" />}
        {forgot.isPending ? t('auth.reset.request.sending') : action}
      </button>
      {forgot.isSuccess && (
        <p role="status" className="auth-status">
          {t('auth.reset.request.sent', { email: forgot.variables })}
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

/** Renders `children` only while the backend offers password reset; `/features` failing counts as off. */
export function PasswordResetGate({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const features = useFeaturesQuery();
  if (features.isPending) {
    return (
      <p role="status" className="auth-subtitle">
        {t('auth.reset.gate.loading')}
      </p>
    );
  }
  if (!features.data?.passwordReset) {
    return <p className="auth-subtitle">{t('auth.reset.gate.unavailable')}</p>;
  }
  return <>{children}</>;
}
