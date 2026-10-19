import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { AuthPoseProvider } from './authPose';
import { AuthShell } from './AuthShell';
import type { NodiPose } from './nodiPeekState';

/**
 * Layout route of the public auth screens: the banner stage and card frame stay mounted while
 * the card content swaps, and a page that throws is contained inside the card.
 */
export function AuthLayout() {
  const { pathname } = useLocation();
  const [pose, setPose] = useState<NodiPose>('idle');

  return (
    <AuthPoseProvider value={setPose}>
      <AuthShell pose={pose}>
        <ErrorBoundary key={pathname}>
          <Outlet />
        </ErrorBoundary>
      </AuthShell>
    </AuthPoseProvider>
  );
}
