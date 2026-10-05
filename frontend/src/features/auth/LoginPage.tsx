import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { useAuthToken, useLoginMutation } from './useAuth';

/**
 * Email+password → token → guarded route. Functional MVP only — no design
 * tokens yet.
 */
export function LoginPage() {
  const token = useAuthToken();
  const navigate = useNavigate();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({ resolver: zodResolver(loginSchema) });

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    loginMutation.mutate(values, {
      onSuccess: () => navigate('/', { replace: true }),
    });
  });

  const serverError =
    loginMutation.error instanceof ApiError ? loginMutation.error.problem?.detail : undefined;

  return (
    <main className="auth-page">
      <section className="auth-card">
        <p className="eyebrow">FLOW-CORTEX · CREATOR STUDIO</p>
        <form onSubmit={onSubmit} noValidate aria-label="Sign in">
          <h1>Sign in</h1>
          <div className="field">
            <label htmlFor="login-email">Email</label>
            <input id="login-email" type="email" autoComplete="username" {...register('email')} />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button className="primary-button" type="submit" disabled={loginMutation.isPending}>
            {loginMutation.isPending ? 'Signing in…' : 'Sign in to Studio →'}
          </button>
        </form>
        <p className="auth-footer">
          New to Flow-Cortex? <Link to="/register">Create a free account</Link>
        </p>
      </section>
    </main>
  );
}
