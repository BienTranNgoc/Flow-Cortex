import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import './styles/tokens.css';
import './app/base.css';
import { AppShell } from './app/AppShell';
import { DocsPage } from './app/DocsPage';
import { HomePage } from './app/HomePage';
import { IntegrationsPage } from './app/IntegrationsPage';
import { Layout } from './app/Layout';
import { queryClient } from './app/queryClient';
import { RunsPage } from './app/RunsPage';
import { LoginPage } from './features/auth/LoginPage';
import { RegisterPage } from './features/auth/RegisterPage';
import { ProfilePage } from './features/auth/ProfilePage';
import { RequireAuth } from './features/auth/RequireAuth';
import { RunProgressPage } from './features/workflow/RunProgressPage';
import { WorkflowDetailPage } from './features/workflow/WorkflowDetailPage';
import { WorkflowsListPage } from './features/workflow/WorkflowsListPage';

/** The page area inside the layout; keyed by path so navigating away from a failed page clears it. */
function ContainedOutlet() {
  const { pathname } = useLocation();
  return (
    <ErrorBoundary key={pathname}>
      <Outlet />
    </ErrorBoundary>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route
            element={
              <AppShell>
                <Outlet />
              </AppShell>
            }
          >
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
          </Route>
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route element={<ContainedOutlet />}>
              <Route index element={<HomePage />} />
              <Route path="profile" element={<ProfilePage />} />
              <Route path="workflows" element={<WorkflowsListPage />} />
              <Route path="workflows/:id" element={<WorkflowDetailPage />} />
              <Route path="workflows/:workflowId/runs/:runId" element={<RunProgressPage />} />
              <Route path="runs" element={<RunsPage />} />
              <Route path="runs/:runId" element={<RunProgressPage />} />
              <Route path="integrations" element={<IntegrationsPage />} />
              <Route path="docs" element={<DocsPage />} />
              <Route path="docs/api" element={<DocsPage initialTab="api" />} />
              <Route path="docs/guides" element={<DocsPage initialTab="guides" />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
