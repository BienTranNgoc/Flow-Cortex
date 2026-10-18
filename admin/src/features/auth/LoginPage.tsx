import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { AlertCircle, ArrowRight, Loader2, Shield } from 'lucide-react';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { usePageTitle } from '../../app/usePageTitle';
import { useAuthToken, useLoginMutation } from './useAuth';

/** Email+password → token; once a token is stored, returns to the page that sent the visitor here. */
export function LoginPage() {
  usePageTitle('Sign in');
  const token = useAuthToken();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({ resolver: zodResolver(loginSchema) });

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/runs';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => loginMutation.mutate(values));

  const serverError =
    loginMutation.error instanceof ApiError ? loginMutation.error.problem?.detail : undefined;

  return (
    <main className="auth-shell">
      <aside className="auth-shell__aside" aria-hidden="true">
        <div className="auth-shell__aurora" />
        <div className="auth-shell__orb auth-shell__orb--cyan" />
        <div className="auth-shell__orb auth-shell__orb--violet" />
        <div className="auth-shell__orb auth-shell__orb--teal" />
        <div className="auth-shell__aside-grid" />
        <div className="auth-shell__aside-content">
          <div className="auth-shell__brand">
            <span className="auth-shell__brand-mark">
              <Shield size={18} />
            </span>
            <div className="auth-shell__brand-text">
              <strong>FlowCortex</strong>
              <span>Admin console</span>
            </div>
          </div>
          <div className="auth-shell__pitch">
            <h2>Operate every workflow from one place.</h2>
            <p>
              Audit runs, manage members, control credits and feature switches — built for
              Flow-Cortex operators.
            </p>
            <ul className="auth-shell__pitch-list">
              <li>
                <span className="auth-shell__pitch-dot" />
                Real-time run audit and approval queue
              </li>
              <li>
                <span className="auth-shell__pitch-dot" />
                Member roles, wallets, and SMS outbox
              </li>
              <li>
                <span className="auth-shell__pitch-dot" />
                Feature switches and provider model rotation
              </li>
            </ul>
          </div>
          <p className="auth-shell__aside-foot">© Flow-Cortex · Restricted to authorized operators</p>
        </div>
      </aside>

      <section className="auth-shell__form-pane">
        <div className="auth-shell__form-inner">
          <header className="auth-shell__form-header">
            <span className="auth-shell__eyebrow">Flow-Cortex administration</span>
            <h1>Sign in</h1>
            <p>Use your operator email and password to access the console.</p>
          </header>

          <form onSubmit={onSubmit} noValidate aria-label="Sign in" className="auth-form">
            <div className="auth-form__field">
              <label htmlFor="login-email" className="auth-form__label">
                Email
              </label>
              <input
                id="login-email"
                type="email"
                autoComplete="username"
                className={`auth-form__input${errors.email ? ' is-invalid' : ''}`}
                placeholder="operator@flow-cortex.app"
                {...register('email')}
              />
              {errors.email && (
                <p className="auth-form__error" role="alert">
                  <AlertCircle aria-hidden="true" size={14} />
                  {errors.email.message}
                </p>
              )}
            </div>

            <div className="auth-form__field">
              <label htmlFor="login-password" className="auth-form__label">
                Password
              </label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                className={`auth-form__input${errors.password ? ' is-invalid' : ''}`}
                placeholder="••••••••"
                {...register('password')}
              />
              {errors.password && (
                <p className="auth-form__error" role="alert">
                  <AlertCircle aria-hidden="true" size={14} />
                  {errors.password.message}
                </p>
              )}
            </div>

            {serverError && (
              <p className="auth-form__error auth-form__error--server" role="alert">
                <AlertCircle aria-hidden="true" size={14} />
                {serverError}
              </p>
            )}

            <button
              type="submit"
              className="auth-form__submit"
              disabled={loginMutation.isPending}
            >
              {loginMutation.isPending ? (
                <>
                  <Loader2 aria-hidden="true" size={16} className="auth-form__spinner" />
                  Signing in…
                </>
              ) : (
                <>
                  Sign in
                  <ArrowRight aria-hidden="true" size={16} />
                </>
              )}
            </button>
          </form>

          <footer className="auth-shell__form-foot">
            <span>Need access?</span>
            <a href="mailto:ops@flow-cortex.app">Contact your platform owner</a>
          </footer>
        </div>
      </section>
    </main>
  );
}
