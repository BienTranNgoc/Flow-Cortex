import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { OAuthButton } from '../../app/OAuthButton';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { useAuthToken, useLoginMutation, getRememberedEmail, setRememberedEmail, clearRememberedEmail, type LoginVariables } from './useAuth';

type SubmitState = 'idle' | 'pending' | 'success';

export function LoginPage() {
  const token = useAuthToken();
  const navigate = useNavigate();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const [submitState, setSubmitState] = useState<SubmitState>('idle');
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: getRememberedEmail(), password: '', rememberMe: true },
  });

  useEffect(() => {
    if (loginMutation.isError) {
      setSubmitState('idle');
    }
  }, [loginMutation.isError]);

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    setSubmitState('pending');
    if (values.rememberMe) {
      setRememberedEmail(values.email);
    } else {
      clearRememberedEmail();
    }
    loginMutation.mutate(values as unknown as LoginVariables, {
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
    loginMutation.error instanceof ApiError ? loginMutation.error.problem?.detail : undefined;

  const buttonLabel =
    submitState === 'success'
      ? '✓ Signed in'
      : submitState === 'pending'
        ? 'Signing in…'
        : 'Sign in to Studio →';

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
        <h1>SIGN IN</h1>
        <p className="auth-subtitle">Access your creator workspace and run media pipelines.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Continue with Google"
            onSelect={(p) => window.console.info(`[ui-only] oauth: ${p}`)}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>or sign in with email</span>
        </div>

        <form onSubmit={onSubmit} noValidate aria-label="Sign in" className="auth-form">
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="login-email">Email</label>
            <input
              id="login-email"
              type="email"
              autoComplete="username"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <label className="remember-row" htmlFor="login-remember">
            <input
              id="login-remember"
              type="checkbox"
              {...register('rememberMe')}
            />
            <span>Remember me on this device</span>
          </label>
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
          New to Flow-Cortex? <Link to="/register">Create a free account</Link>
        </p>
      </section>
    </div>
  );
}
