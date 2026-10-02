import { logout } from '../features/auth/useAuth';
import { usePageTitle } from './usePageTitle';

export function NoAccessPage({ email }: Readonly<{ email: string | undefined }>) {
  usePageTitle('No access');
  return (
    <main className="auth-page">
      <h1>No access</h1>
      <p>
        {email ? <strong>{email}</strong> : 'This account'} does not have the ADMIN role, so it cannot
        use the admin console.
      </p>
      <button type="button" onClick={logout}>
        Sign out
      </button>
    </main>
  );
}
