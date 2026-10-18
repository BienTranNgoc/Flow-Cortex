import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './app/base.css';
import { AdminLayout } from './app/AdminLayout';
import { reachableItems } from './app/nav';
import { queryClient } from './app/queryClient';
import { RequireAdminApp } from './app/RequireAdminApp';
import { LoginPage } from './features/auth/LoginPage';
import { useMeQuery } from './features/auth/useAuth';
import { DashboardPage } from './features/dashboard/DashboardPage';
import { MediaJobsPage } from './features/mediaJobs/MediaJobsPage';
import { RunsPage } from './features/runs/RunsPage';
import { UserDetailPage } from './features/users/UserDetailPage';
import { UsersPage } from './features/users/UsersPage';

const PAGES: Record<string, ReactElement> = {
  '/dashboard': <DashboardPage />,
  '/runs': <RunsPage />,
  '/media-jobs': <MediaJobsPage />,
  '/users': <UsersPage />,
};

/** Routes exist only for the entries the role may open; anything else lands on the first of them. */
function AdminRoutes() {
  const items = reachableItems(useMeQuery().data?.role);
  const home = items[0]?.path;

  return (
    <Routes>
      <Route element={<AdminLayout />}>
        {items.map((item) => (
          <Route key={item.path} path={item.path} element={PAGES[item.path]} />
        ))}
        {items.some((item) => item.path === '/users') && (
          <Route path="/users/:userId" element={<UserDetailPage />} />
        )}
        <Route
          path="*"
          element={
            home ? <Navigate to={home} replace /> : <p>No page is available to this account.</p>
          }
        />
      </Route>
    </Routes>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="*"
            element={
              <RequireAdminApp>
                <AdminRoutes />
              </RequireAdminApp>
            }
          />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
