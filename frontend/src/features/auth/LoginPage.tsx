import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { loginSchema, type LoginFormValues } from './loginSchema';
import {
  clearRememberedEmail,
  detailFrom,
  getRememberedEmail,
  setRememberedEmail,
  useAuthToken,
  useLoginMutation,
  useSubmitState,
  type LoginVariables,
} from './useAuth';

export function LoginPage() {
  const token = useAuthToken();
  const navigate = useNavigate();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const submit = useSubmitState(loginMutation);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: getRememberedEmail(), password: '', rememberMe: true },
  });

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    submit.setState('pending');
    if (values.rememberMe) {
      setRememberedEmail(values.email);
    } else {
      clearRememberedEmail();
    }
    loginMutation.mutate(values as unknown as LoginVariables, {
      onSuccess: () => {
        submit.setState('success');
        window.setTimeout(() => navigate('/', { replace: true }), 220);
      },
      onError: () => {
        submit.setState('idle');
      },
    });
  });

  const serverError = detailFrom(loginMutation);

  const buttonLabel = (() => {
    if (submit.state === 'success') return '✓ Signed in';
    if (submit.state === 'pending') return 'Signing in…';
    return 'Sign in to Studio →';
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

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
            data-state={submit.state}
            disabled={submit.state !== 'idle'}
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
