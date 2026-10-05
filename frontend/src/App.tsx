import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import './app/base.css';
import { HomePage } from './app/HomePage';
import { Layout } from './app/Layout';
import { queryClient } from './app/queryClient';
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
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
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
              <Route path="runs/:runId" element={<RunProgressPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
