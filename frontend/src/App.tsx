import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './app/base.css';
import { HomePage } from './app/HomePage';
import { Layout } from './app/Layout';
import { queryClient } from './app/queryClient';
import { LoginPage } from './features/auth/LoginPage';
import { ProfilePage } from './features/auth/ProfilePage';
import { RequireAdmin } from './features/auth/RequireAdmin';
import { RequireAuth } from './features/auth/RequireAuth';
import { UsersPage } from './features/users/UsersPage';
import { RunProgressPage } from './features/workflow/RunProgressPage';
import { WorkflowDetailPage } from './features/workflow/WorkflowDetailPage';
import { WorkflowsListPage } from './features/workflow/WorkflowsListPage';

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route index element={<HomePage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route
              path="users"
              element={
                <RequireAdmin>
                  <UsersPage />
                </RequireAdmin>
              }
            />
            <Route path="workflows" element={<WorkflowsListPage />} />
            <Route path="workflows/:id" element={<WorkflowDetailPage />} />
            <Route path="workflows/:workflowId/runs/:runId" element={<RunProgressPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
