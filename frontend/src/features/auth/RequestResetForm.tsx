import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { useFeaturesQuery } from '../../app/useFeatures';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
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
export function RequestResetForm({ action }: Readonly<RequestResetFormProps>) {
  const { t } = useTranslation();
  const forgot = useForgotPasswordMutation();
  const [typedEmail, setTypedEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const [focused, setFocused] = useState<'email' | null>(null);
  const [editedSinceSubmit, setEditedSinceSubmit] = useState(false);
  const editVersion = useRef(0);
  const emailInput = useRef<HTMLInputElement>(null);

  useAuthPose(
    nodiPeekState({
      focused,
      pending: forgot.isPending,
      failed: Boolean(emailError) || (forgot.isError && !editedSinceSubmit),
      succeeded: forgot.isSuccess,
    }),
  );

  const refuseEmail = (message: string) => {
    setEmailError(message);
    emailInput.current?.focus();
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setEditedSinceSubmit(false);
    const parsed = resendEmailSchema.safeParse(typedEmail);
    if (!parsed.success) {
      forgot.reset();
      refuseEmail(t(parsed.error.issues[0]?.message ?? 'auth.validation.emailInvalid'));
      return;
    }
    setEmailError(null);
    const submittedVersion = editVersion.current;
    forgot.mutate(parsed.data, {
      onError: (error) => {
        if (editVersion.current !== submittedVersion) return;
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
            placeholder={t('auth.reset.request.emailPlaceholder')}
            value={typedEmail}
            onChange={(event) => {
              editVersion.current += 1;
              setTypedEmail(event.target.value);
              setEditedSinceSubmit(true);
              if (emailError) setEmailError(null);
            }}
            onFocus={() => setFocused('email')}
            onBlur={() => setFocused(null)}
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
        <output className="auth-status">
          {t('auth.reset.request.sent', { email: forgot.variables })}
        </output>
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
export function PasswordResetGate({ children }: { readonly children: ReactNode }) {
  const { t } = useTranslation();
  const features = useFeaturesQuery();
  if (features.isPending) {
    return (
      <output className="auth-subtitle">
        {t('auth.reset.gate.loading')}
      </output>
    );
  }
  if (!features.data?.passwordReset) {
    return <p className="auth-subtitle">{t('auth.reset.gate.unavailable')}</p>;
  }
  return <>{children}</>;
}
