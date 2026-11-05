import { Link } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { PasswordResetGate, RequestResetForm } from './RequestResetForm';

export function ForgotPasswordPage() {
  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">CREATOR ACCESS · RESET PASSWORD</p>
        <h1 className="auth-card__title">Reset your password</h1>

        <PasswordResetGate>
          <p className="auth-subtitle">
            Enter the email you sign in with and we will send you a link to choose a new password.
          </p>
          <RequestResetForm action="Send reset link" />
        </PasswordResetGate>

        <p className="auth-footer">
          <Link to="/login">Back to sign in</Link>
        </p>
      </section>
    </div>
  );
}
