import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
  const [pose, setPose] = useState<NodiPose>('idle');

  return (
    <AuthPoseProvider value={setPose}>
      <AuthShell pose={pose}>
        <ErrorBoundary
          key={pathname}
          labels={{
            title: t('common.errorView.title'),
            message: t('common.errorView.message'),
            reload: t('common.errorView.reload'),
          }}
        >
          <Outlet />
        </ErrorBoundary>
      </AuthShell>
    </AuthPoseProvider>
  );
}
