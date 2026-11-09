import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import './styles/tokens.css';
import './app/base.css';
import { AccountCacheReset } from './app/AccountCacheReset';
import { DocsPage } from './app/DocsPage';
import { demoPagesEnabled } from './app/featureFlags';
import { HomePage } from './app/HomePage';
import { IntegrationsPage } from './app/IntegrationsPage';
import { Layout } from './app/Layout';
import { queryClient } from './app/queryClient';
import { RunsPage } from './app/RunsPage';
import { useFeatures } from './app/useFeatures';
import { AuthLayout } from './features/auth/AuthLayout';
import { ConfirmEmailPage } from './features/auth/ConfirmEmailPage';
import { ForgotPasswordPage } from './features/auth/ForgotPasswordPage';
import { LoginPage } from './features/auth/LoginPage';
import { PasswordSetupGate } from './features/auth/PasswordSetupGate';
import { RegisterPage } from './features/auth/RegisterPage';
import { ProfilePage } from './features/auth/ProfilePage';
import { RequireAuth } from './features/auth/RequireAuth';
import { ResetPasswordPage } from './features/auth/ResetPasswordPage';
import { SetPasswordPage } from './features/auth/SetPasswordPage';
import { RunProgressPage } from './features/workflow/RunProgressPage';
import { WorkflowDetailPage } from './features/workflow/WorkflowDetailPage';
import { WorkflowsListPage } from './features/workflow/WorkflowsListPage';

/** A route's page area; keyed by path so navigating away from a failed page clears it. */
function ContainedOutlet() {
  const { pathname } = useLocation();
  const { t } = useTranslation();
  return (
    <ErrorBoundary
      key={pathname}
      labels={{
        title: t('common.errorView.title'),
        message: t('common.errorView.message'),
        reload: t('common.errorView.reload'),
        retry: t('common.errorView.retry'),
      }}
    >
      <Outlet />
    </ErrorBoundary>
  );
}

const SupportWidget = lazy(() =>
  import('./features/support/SupportWidget').then((module) => ({ default: module.SupportWidget })),
);

function SupportWidgetWhenOn() {
  const { supportWidget } = useFeatures();
  return supportWidget ? (
    <ErrorBoundary fallback={null}>
      <Suspense fallback={null}>
        <SupportWidget />
      </Suspense>
    </ErrorBoundary>
  ) : null;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AccountCacheReset />
      <BrowserRouter>
        <Routes>
          <Route element={<AuthLayout />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/confirm-email" element={<ConfirmEmailPage />} />
            {/* Not behind RequireAuth: the emailed link opens it signed out. */}
            <Route path="/set-password" element={<SetPasswordPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
          </Route>
          <Route
            element={
              <RequireAuth>
                <PasswordSetupGate>
                  <Layout />
                </PasswordSetupGate>
              </RequireAuth>
            }
          >
            <Route element={<ContainedOutlet />}>
              <Route index element={<HomePage />} />
              <Route path="profile" element={<ProfilePage />} />
              <Route path="workflows" element={<WorkflowsListPage />} />
              <Route path="workflows/:id" element={<WorkflowDetailPage />} />
              <Route path="workflows/:workflowId/runs/:runId" element={<RunProgressPage />} />
              {demoPagesEnabled() && <Route path="runs" element={<RunsPage />} />}
              <Route path="runs/:runId" element={<RunProgressPage />} />
              {demoPagesEnabled() && <Route path="integrations" element={<IntegrationsPage />} />}
              <Route path="docs" element={<DocsPage />} />
              <Route path="docs/api" element={<DocsPage initialTab="api" />} />
              <Route path="docs/guides" element={<DocsPage initialTab="guides" />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        <SupportWidgetWhenOn />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
