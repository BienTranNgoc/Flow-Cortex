import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useMeQuery } from './useAuth';

/**
 * Inside `RequireAuth`: an account with no password and no Google sign-in has no way back in once
 * this session ends, so it is sent to `/set-password` until it has one. A failed `/me` lets the
 * page through; the page reports its own errors.
 */
export function PasswordSetupGate({ children }: Readonly<{ children: ReactNode }>) {
  const me = useMeQuery();

  if (me.isPending) {
    return <output>Checking your session…</output>;
  }
  if (me.data?.passwordSetupRequired) {
    return <Navigate to="/set-password" replace />;
  }
  return <>{children}</>;
}
