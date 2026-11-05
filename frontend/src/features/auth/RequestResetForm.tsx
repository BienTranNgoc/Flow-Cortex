import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useFeaturesQuery } from '../../app/useFeatures';
import { resendEmailSchema } from './registerSchema';
import {
  detailFrom,
  fieldErrorsFrom,
  resetWaitMessage,
  useForgotPasswordMutation,
} from './useAuth';

function refusalMessage(error: unknown, detail: string | undefined): string {
  return resetWaitMessage(error) ?? detail ?? 'Could not send a reset link. Please try again.';
}

interface RequestResetFormProps {
  action: string;
}

/** Asks for a reset link; the answer repeats the typed address and never says whether it has an account. */
export function RequestResetForm({ action }: RequestResetFormProps) {
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
      refuseEmail(parsed.error.issues[0]?.message ?? 'Enter a valid email address');
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
    forgot.isError && !fieldErrorsFrom(forgot.error).email
      ? refusalMessage(forgot.error, detailFrom(forgot))
      : null;

  return (
    <form onSubmit={onSubmit} noValidate aria-label="Request a reset link" className="auth-form">
      <div className="field">
        <label htmlFor="reset-request-email">Email</label>
        <input
          id="reset-request-email"
          ref={emailInput}
          type="email"
          autoComplete="email"
          value={typedEmail}
          onChange={(event) => setTypedEmail(event.target.value)}
          aria-invalid={emailError ? 'true' : 'false'}
          aria-describedby={emailError ? 'reset-request-email-error' : undefined}
        />
        {emailError && (
          <p id="reset-request-email-error" role="alert">
            {emailError}
          </p>
        )}
      </div>
      <button className="primary-button" type="submit" disabled={forgot.isPending}>
        {forgot.isPending ? 'Sending…' : action}
      </button>
      {forgot.isSuccess && (
        <p role="status" className="auth-subtitle">
          If an account exists for {forgot.variables}, we sent a reset link. Didn&apos;t get it?
          Check spam or try again.
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

/** Renders `children` only while the backend offers password reset; `/features` failing counts as off. */
export function PasswordResetGate({ children }: { children: ReactNode }) {
  const features = useFeaturesQuery();
  if (features.isPending) {
    return (
      <p role="status" className="auth-subtitle">
        Loading…
      </p>
    );
  }
  if (!features.data?.passwordReset) {
    return <p className="auth-subtitle">Password reset is not available right now</p>;
  }
  return <>{children}</>;
}
