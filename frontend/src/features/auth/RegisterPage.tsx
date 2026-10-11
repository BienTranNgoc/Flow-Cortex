import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { OAuthButton } from '../../app/OAuthButton';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import { useAuthToken, useRegisterMutation } from './useAuth';

type SubmitState = 'idle' | 'pending' | 'success';

export function RegisterPage() {
  const token = useAuthToken();
  const navigate = useNavigate();
  const registerMutation = useRegisterMutation();
  const [submitState, setSubmitState] = useState<SubmitState>('idle');
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormValues>({ resolver: zodResolver(registerSchema) });

  useEffect(() => {
    if (registerMutation.isError) {
      setSubmitState('idle');
    }
  }, [registerMutation.isError]);

  if (token) {
    return <Navigate to="/" replace />;
  }

  const onSubmit = handleSubmit(({ confirmPassword: _confirmPassword, ...values }) => {
    setSubmitState('pending');
    registerMutation.mutate(values, {
      onSuccess: () => {
        setSubmitState('success');
        window.setTimeout(() => navigate('/', { replace: true }), 220);
      },
      onError: () => {
        setSubmitState('idle');
      },
    });
  });
  const serverError =
    registerMutation.error instanceof ApiError
      ? registerMutation.error.problem?.detail
      : undefined;

  const buttonLabel =
    submitState === 'success'
      ? '✓ Account created'
      : submitState === 'pending'
        ? 'Creating account…'
        : 'Get started free →';

  return (
    <div className="auth-page">
      <aside className="brand-panel" aria-hidden="false">
        <div className="brand-panel__inner">
          <p className="eyebrow">FLOWCORTEX AI 2.5</p>
          <h2 className="brand-panel__headline">
            Your AI media <span>studio</span> for production-grade pipelines.
          </h2>
          <p className="brand-panel__lede">
            Source, process, review and export media in one click — built for creators
            who need consistent output, not another toy.
          </p>
          <ul className="brand-panel__bullets">
            <li>
              <span className="brand-panel__bullet-dot" aria-hidden="true" />
              Three production pipelines ready out of the box
            </li>
            <li>
              <span className="brand-panel__bullet-dot" aria-hidden="true" />
              Human review gates built into every multi-step run
            </li>
            <li>
              <span className="brand-panel__bullet-dot" aria-hidden="true" />
              Output retention and signed download links by default
            </li>
          </ul>
        </div>
      </aside>

      <section className="auth-card">
        <p className="eyebrow">CREATOR ACCESS · SIGN UP</p>
        <h1>Create your free creator account</h1>
        <p className="auth-subtitle">Start generating visual workflows in minutes.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Sign up with Google"
            onSelect={(p) => window.console.info(`[ui-only] oauth: ${p}`)}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>or sign up with email</span>
        </div>

        <form onSubmit={onSubmit} noValidate aria-label="Create account" className="auth-form">
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="register-name">Full name</label>
            <input
              id="register-name"
              autoComplete="name"
              aria-invalid={errors.name ? 'true' : 'false'}
              {...register('name')}
            />
            {errors.name && <p role="alert">{errors.name.message}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="register-email">Work or personal email</label>
            <input
              id="register-email"
              type="email"
              autoComplete="email"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field" style={{ '--i': 2 } as React.CSSProperties}>
            <label htmlFor="register-password">Password</label>
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <div className="field" style={{ '--i': 3 } as React.CSSProperties}>
            <label htmlFor="register-confirm-password">Confirm password</label>
            <input
              id="register-confirm-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.confirmPassword ? 'true' : 'false'}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword && (
              <p role="alert">{errors.confirmPassword.message}</p>
            )}
          </div>
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button
            className="primary-button"
            type="submit"
            data-state={submitState}
            disabled={submitState !== 'idle'}
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
