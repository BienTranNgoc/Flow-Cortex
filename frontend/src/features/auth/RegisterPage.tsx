import { zodResolver } from '@hookform/resolvers/zod';
import { useState, type CSSProperties } from 'react';
import { useForm, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { Link, Navigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { ResendConfirmation } from './ConfirmEmailPage';
import { googleAuthorizeUrl } from './oauth';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import {
  detailFrom,
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
  const id = `register-${name}`;
  const message = errors[name]?.message;
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
          <p className="eyebrow">CREATOR ACCESS · SIGN UP</p>
          <div role="status">
            <h1 className="auth-card__title">Check your inbox to confirm your email</h1>
            <p className="auth-subtitle">
              We sent a confirmation link to <strong>{sentTo}</strong>. Open it within 24 hours to
              activate your account and sign in.
            </p>
          </div>
          <ResendConfirmation email={sentTo} />
          <p className="auth-footer">
            Already confirmed? <Link to="/login">Sign in to workspace</Link>
          </p>
        </section>
      </div>
    );
  }

  // A refusal shown at its field is not repeated under the form.
  const refusedFields = fieldErrorsFrom(registerMutation.error);
  const serverError = SERVER_FIELDS.some((field) => refusedFields[field])
    ? undefined
    : detailFrom(registerMutation);
  const isPending = registerMutation.isPending;
  const dataState = submitState(registerMutation.isSuccess, isPending);
  const fieldProps = { register, errors };

  const buttonLabel = (() => {
    if (registerMutation.isSuccess) return '✓ Account created';
    if (isPending) return 'Creating account…';
    return 'Get started free →';
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">CREATOR ACCESS · SIGN UP</p>
        <h1 className="auth-card__title">Create your free creator account</h1>
        <p className="auth-subtitle">Start generating visual workflows in minutes.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Sign up with Google"
            href={googleAuthorizeUrl(true, '/')}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>or sign up with email</span>
        </div>

        <form onSubmit={onSubmit} noValidate aria-label="Create account" className="auth-form">
          <Field
            name="firstName"
            label="First name"
            index={0}
            autoComplete="given-name"
            {...fieldProps}
          />
          <Field
            name="lastName"
            label="Last name"
            index={1}
            autoComplete="family-name"
            {...fieldProps}
          />
          <Field
            name="email"
            label="Email"
            index={2}
            type="email"
            autoComplete="email"
            {...fieldProps}
          />
          <Field
            name="username"
            label="Username (optional)"
            index={3}
            autoComplete="username"
            {...fieldProps}
          />
          <Field
            name="password"
            label="Password"
            index={4}
            type="password"
            autoComplete="new-password"
            {...fieldProps}
          />
          <Field
            name="confirmPassword"
            label="Confirm password"
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
          Already have an account? <Link to="/login">Sign in to workspace</Link>
        </p>
      </section>
    </div>
  );
}
