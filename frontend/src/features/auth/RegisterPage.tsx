import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { Navigate } from 'react-router-dom';
import { OAuthButton } from '../../app/OAuthButton';
import { AuthLink } from './AuthLink';
import { authCopy } from './authCopy';
import { useAuthPose } from './authPose';
import { ResendConfirmation } from './ConfirmEmailPage';
import { nodiPeekState, type NodiPeekInput } from './nodiPeekState';
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
type Focused = NodiPeekInput['focused'];

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
  type?: 'text' | 'email' | 'password';
  autoComplete: string;
  /** Which way the card mascot turns while this field has focus. */
  focusAs?: Focused;
  onFocused: (focused: Focused) => void;
  onEdited: () => void;
  register: UseFormRegister<RegisterFormValues>;
  errors: FieldErrors<RegisterFormValues>;
}

function Field({
  name,
  type = 'text',
  autoComplete,
  focusAs = null,
  onFocused,
  onEdited,
  register,
  errors,
}: FieldProps) {
  const id = `register-${name}`;
  const message = errors[name]?.message;
  const { label, placeholder } = authCopy.register.fields[name];
  const field = register(name, { onChange: onEdited });
  return (
    <div className="auth-field">
      <label htmlFor={id} className="auth-label">
        {label}
      </label>
      <div className="auth-control">
        <input
          id={id}
          type={type}
          className="auth-input"
          autoComplete={autoComplete}
          placeholder={placeholder}
          aria-invalid={message ? 'true' : 'false'}
          aria-describedby={message ? `${id}-error` : undefined}
          {...field}
          onFocus={() => onFocused(focusAs)}
          onBlur={(event) => {
            onFocused(null);
            return field.onBlur(event);
          }}
        />
      </div>
      {message && (
        <p id={`${id}-error`} role="alert" className="auth-error">
          {message}
        </p>
      )}
    </div>
  );
}

export function RegisterPage() {
  const copy = authCopy.register;
  const token = useAuthToken();
  const registerMutation = useRegisterMutation();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [focused, setFocused] = useState<Focused>(null);
  const [editedSinceSubmit, setEditedSinceSubmit] = useState(false);
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
  const isPending = registerMutation.isPending;
  useAuthPose(
    nodiPeekState({
      focused,
      pending: isPending,
      failed: registerMutation.isError && !editedSinceSubmit,
      succeeded: registerMutation.isSuccess,
    }),
  );

  if (token) {
    return <Navigate to="/" replace />;
  }

  const onSubmit = handleSubmit(({ confirmPassword: _confirmPassword, username, ...values }) => {
    setEditedSinceSubmit(false);
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
      <>
        <h1 className="auth-title" tabIndex={-1}>
          {copy.sentTitle}
        </h1>
        <p className="auth-status" role="status">
          {copy.sentBefore} <strong>{sentTo}</strong>
          {copy.sentAfter}
        </p>
        <ResendConfirmation email={sentTo} />
        <p className="auth-footnote">
          {copy.alreadyConfirmed} <AuthLink to="/login">{copy.signIn}</AuthLink>
        </p>
      </>
    );
  }

  // A refusal shown at its field is not repeated above the form.
  const refusedFields = fieldErrorsFrom(registerMutation.error);
  const serverError =
    !registerMutation.isError || SERVER_FIELDS.some((field) => refusedFields[field])
      ? undefined
      : (detailFrom(registerMutation) ?? copy.retryLater);
  const dataState = submitState(registerMutation.isSuccess, isPending);
  const fieldProps = {
    register,
    errors,
    onFocused: setFocused,
    onEdited: () => setEditedSinceSubmit(true),
  };

  const buttonLabel = (() => {
    if (registerMutation.isSuccess) return copy.submitSuccess;
    if (isPending) return copy.submitPending;
    return copy.submit;
  })();

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {copy.title} <span className="auth-title__accent">{copy.titleAccent}</span>
      </h1>
      <p className="auth-subtitle">{copy.subtitle}</p>

      {serverError && (
        <p id="register-alert" role="alert" className="auth-alert">
          {serverError}
        </p>
      )}

      <form onSubmit={onSubmit} noValidate aria-label={copy.formLabel}>
        <Field name="firstName" autoComplete="given-name" {...fieldProps} />
        <Field name="lastName" autoComplete="family-name" {...fieldProps} />
        <Field name="email" type="email" autoComplete="email" focusAs="email" {...fieldProps} />
        <Field name="username" autoComplete="username" {...fieldProps} />
        <Field
          name="password"
          type="password"
          autoComplete="new-password"
          focusAs="password"
          {...fieldProps}
        />
        <Field
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          focusAs="password"
          {...fieldProps}
        />
        <button
          className="auth-btn auth-btn--primary"
          type="submit"
          data-state={dataState}
          disabled={isPending}
          aria-busy={isPending}
        >
          {isPending && <span className="auth-spinner" aria-hidden="true" />}
          {buttonLabel}
        </button>
      </form>

      <hr className="auth-divider" />
      <OAuthButton
        provider="google"
        label={copy.google}
        href={googleAuthorizeUrl(true, '/')}
        className="auth-btn auth-btn--google"
      />

      <p className="auth-footnote">
        {copy.haveAccount} <AuthLink to="/login">{copy.signIn}</AuthLink>
      </p>
    </>
  );
}
