import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation, useNavigate, type Path } from 'react-router-dom';
import { PhoneVerification } from './PhoneVerification';
import { usePhoneQuery } from './usePhone';
import { logout, useMeQuery } from './useAuth';
import './VerifyPhonePage.css';

/** The page a held member asked for, as `PhoneGate` left it in router state; `/` when there is none. */
function returnTarget(state: unknown): Partial<Path> | string {
  const from = (state as { from?: Partial<Path> } | null)?.from;
  return typeof from?.pathname === 'string' && from.pathname.startsWith('/') ? from : '/';
}

/**
 * The wall for a member the server holds until a phone number is verified. Once `/me` no longer
 * requires it (verified here, or in another tab) the member goes back to the page they asked for.
 */
export function VerifyPhonePage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const me = useMeQuery();
  const verified = usePhoneQuery().data?.verified === true;
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    if (verified) {
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    }
  }, [verified, queryClient]);

  if (me.isPending) {
    return (
      <p role="status" className="auth-status">
        {t('auth.session.checking')}
      </p>
    );
  }
  if (me.data && !me.data.phoneVerificationRequired) {
    return <Navigate to={returnTarget(location.state)} replace />;
  }

  // The login page sends a signed-in visitor away, so it opens only once the session is gone.
  const onSignOut = () => {
    setSigningOut(true);
    void logout().then(() => navigate('/login', { replace: true }));
  };

  return (
    <div className="verify-phone">
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.phoneGate.title')}
      </h1>
      <p className="auth-subtitle">{t('auth.phoneGate.hint')}</p>
      <PhoneVerification />
      <button
        type="button"
        className="auth-btn auth-btn--secondary"
        onClick={onSignOut}
        disabled={signingOut}
        aria-busy={signingOut}
      >
        {t('auth.phoneGate.signOut')}
      </button>
    </div>
  );
}
