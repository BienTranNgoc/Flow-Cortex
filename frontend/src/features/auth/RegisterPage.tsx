import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import { useAuthToken, useRegisterMutation } from './useAuth';

export function RegisterPage() {
  const token = useAuthToken();
  const navigate = useNavigate();
  const registerMutation = useRegisterMutation();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormValues>({ resolver: zodResolver(registerSchema) });

  if (token) {
    return <Navigate to="/" replace />;
  }

  const onSubmit = handleSubmit(({ confirmPassword: _confirmPassword, ...values }) => {
    registerMutation.mutate(values, {
      onSuccess: () => navigate('/', { replace: true }),
    });
  });
  const serverError =
    registerMutation.error instanceof ApiError ? registerMutation.error.problem?.detail : undefined;

  return (
    <main className="auth-page">
      <section className="auth-card">
        <p className="eyebrow">FLOW-CORTEX · CREATOR ACCESS</p>
        <h1>Create your free creator account</h1>
        <p className="auth-subtitle">Start generating visual workflows in minutes.</p>
        <form onSubmit={onSubmit} noValidate aria-label="Create account">
          <div className="field">
            <label htmlFor="register-name">Full name</label>
            <input id="register-name" autoComplete="name" {...register('name')} />
            {errors.name && <p role="alert">{errors.name.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="register-email">Work or personal email</label>
            <input id="register-email" type="email" autoComplete="email" {...register('email')} />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="register-password">Master password</label>
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="register-confirm-password">Confirm password</label>
            <input
              id="register-confirm-password"
              type="password"
              autoComplete="new-password"
              {...register('confirmPassword')}
            />
            {errors.confirmPassword && <p role="alert">{errors.confirmPassword.message}</p>}
          </div>
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button className="primary-button" type="submit" disabled={registerMutation.isPending}>
            {registerMutation.isPending ? 'Creating account…' : 'Get started free →'}
          </button>
        </form>
        <p className="auth-footer">
          Already have an account? <Link to="/login">Sign in to workspace</Link>
        </p>
      </section>
    </main>
  );
}
