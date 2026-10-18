import { zodResolver } from '@hookform/resolvers/zod';
import { useState, type CSSProperties } from 'react';
import { useForm, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { ResendConfirmation } from './ConfirmEmailPage';
import { googleAuthorizeUrl } from './oauth';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import {
  failureMessage,
  fieldErrorsFrom,
  submitState,
  useAuthToken,
  useRegisterMutation,
} from './useAuth';

type FieldName = keyof RegisterFormValues;

/** Server-side field names the form can show a refusal at. */
const SERVER_FIELDS: readonly FieldName[] = [
  'firstName',
  'lastName',
  'email',
  'username',
  'password',
];

interface FieldProps {
  name: FieldName;
  label: string;
  index: number;
  type?: 'text' | 'email' | 'password';
  autoComplete: string;
  register: UseFormRegister<RegisterFormValues>;
  errors: FieldErrors<RegisterFormValues>;
}

function Field({ name, label, index, type = 'text', autoComplete, register, errors }: FieldProps) {
  const { t } = useTranslation();
  const id = `register-${name}`;
  const error = errors[name];
  // Schema messages are locale keys; a server refusal arrives already worded and is shown as is.
  const message = error?.message && (error.type === 'server' ? error.message : t(error.message));
  return (
    <div className="field" style={{ '--i': index } as CSSProperties}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type={type}
        autoComplete={autoComplete}
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
}

export function RegisterPage() {
  const { t } = useTranslation();
  const token = useAuthToken();
  const registerMutation = useRegisterMutation();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      email: '',
      username: '',
      password: '',
      confirmPassword: '',
    },
  });

  if (token) {
    return <Navigate to="/" replace />;
  }

  const onSubmit = handleSubmit(({ confirmPassword: _confirmPassword, username, ...values }) => {
    registerMutation.mutate(username ? { ...values, username } : values, {
      onSuccess: (outcome) => {
        if (outcome === 'confirmation_sent') {
          setSentTo(values.email);
        }
      },
      onError: (error) => {
        const fieldErrors = fieldErrorsFrom(error);
        let shown = 0;
        for (const field of SERVER_FIELDS) {
          const message = fieldErrors[field];
          if (message) {
            setError(field, { type: 'server', message }, { shouldFocus: shown === 0 });
            shown += 1;
          }
        }
      },
    });
  });

  if (sentTo !== null) {
    return (
      <div className="auth-page">
        <BrandPanel />

        <section className="auth-card">
          <p className="eyebrow">{t('auth.register.eyebrow')}</p>
          <div role="status">
            <h1 className="auth-card__title">{t('auth.register.sent.title')}</h1>
            <p className="auth-subtitle">
              <Trans
                i18nKey="auth.register.sent.body"
                values={{ email: sentTo }}
                components={{ strong: <strong /> }}
              />
            </p>
          </div>
          <ResendConfirmation email={sentTo} />
          <p className="auth-footer">
            {t('auth.register.sent.alreadyConfirmed')}{' '}
            <Link to="/login">{t('auth.register.signIn')}</Link>
          </p>
        </section>
      </div>
    );
  }

  // A refusal shown at its field is not repeated under the form.
  const refusedFields = fieldErrorsFrom(registerMutation.error);
  const serverError = SERVER_FIELDS.some((field) => refusedFields[field])
    ? undefined
    : failureMessage(registerMutation, t);
  const isPending = registerMutation.isPending;
  const dataState = submitState(registerMutation.isSuccess, isPending);
  const fieldProps = { register, errors };

  const buttonLabel = (() => {
    if (registerMutation.isSuccess) return t('auth.register.succeeded');
    if (isPending) return t('auth.register.submitting');
    return t('auth.register.submit');
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">{t('auth.register.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.register.title')}</h1>
        <p className="auth-subtitle">{t('auth.register.subtitle')}</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label={t('auth.register.google')}
            href={googleAuthorizeUrl(true, '/')}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>{t('auth.register.divider')}</span>
        </div>

        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('auth.register.formLabel')}
          className="auth-form"
        >
          <Field
            name="firstName"
            label={t('auth.register.firstNameLabel')}
            index={0}
            autoComplete="given-name"
            {...fieldProps}
          />
          <Field
            name="lastName"
            label={t('auth.register.lastNameLabel')}
            index={1}
            autoComplete="family-name"
            {...fieldProps}
          />
          <Field
            name="email"
            label={t('auth.register.emailLabel')}
            index={2}
            type="email"
            autoComplete="email"
            {...fieldProps}
          />
          <Field
            name="username"
            label={t('auth.register.usernameLabel')}
            index={3}
            autoComplete="username"
            {...fieldProps}
          />
          <Field
            name="password"
            label={t('auth.register.passwordLabel')}
            index={4}
            type="password"
            autoComplete="new-password"
            {...fieldProps}
          />
          <Field
            name="confirmPassword"
            label={t('auth.register.confirmPasswordLabel')}
            index={5}
            type="password"
            autoComplete="new-password"
            {...fieldProps}
          />
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button
            className="primary-button"
            type="submit"
            data-state={dataState}
            disabled={isPending}
          >
            {buttonLabel}
          </button>
        </form>

        <p className="auth-footer">
          {t('auth.register.haveAccount')} <Link to="/login">{t('auth.register.signIn')}</Link>
        </p>
      </section>
    </div>
  );
}
