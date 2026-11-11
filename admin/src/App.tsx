import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './styles/tokens.css';
import './styles/base.css';
import { AdminLayout } from './app/AdminLayout';
import { reachableItems } from './app/nav';
import { queryClient } from './app/queryClient';
import { RequireAdminApp } from './app/RequireAdminApp';
import { LoginPage } from './features/auth/LoginPage';
import { useMeQuery } from './features/auth/useAuth';
import { ChatSettingsPage } from './features/chat/ChatSettingsPage';
import { DashboardPage } from './features/dashboard/DashboardPage';
import { MediaJobsPage } from './features/mediaJobs/MediaJobsPage';
import { OnboardingSamplePage } from './features/onboarding/OnboardingSamplePage';
import { RunsPage } from './features/runs/RunsPage';
import { SmsOutboxPage } from './features/smsOutbox/SmsOutboxPage';
import { ArticleEditorPage } from './features/support/kb/ArticleEditorPage';
import { ArticlesPage } from './features/support/kb/ArticlesPage';
import { TopicGroupsPage } from './features/support/kb/TopicGroupsPage';
import { RequestDetailPage } from './features/support/requests/RequestDetailPage';
import { RequestsInboxPage } from './features/support/requests/RequestsInboxPage';
import { SupportSettingsPage } from './features/support/settings/SupportSettingsPage';
import { UserDetailPage } from './features/users/UserDetailPage';
import { UsersPage } from './features/users/UsersPage';

const PAGES: Record<string, ReactElement> = {
  '/dashboard': <DashboardPage />,
  '/runs': <RunsPage />,
  '/users': <UsersPage />,
  '/sms-outbox': <SmsOutboxPage />,
  '/media-jobs': <MediaJobsPage />,
  '/onboarding-sample': <OnboardingSamplePage />,
  '/support/articles': <ArticlesPage />,
  '/support/groups': <TopicGroupsPage />,
  '/support/requests': <RequestsInboxPage />,
  '/support/settings': <SupportSettingsPage />,
  '/chat/settings': <ChatSettingsPage />,
};

/** Pages under a sidebar entry, routable only where that entry is. */
const CHILD_PAGES: { parent: string; path: string; element: ReactElement }[] = [
  { parent: '/users', path: '/users/:userId', element: <UserDetailPage /> },
  { parent: '/support/articles', path: '/support/articles/:articleId', element: <ArticleEditorPage /> },
  { parent: '/support/requests', path: '/support/requests/:number', element: <RequestDetailPage /> },
];

/** Routes exist only for the entries the role may open; anything else lands on the first of them. */
function AdminRoutes() {
  const { t } = useTranslation();
  const items = reachableItems(useMeQuery().data?.role);
  const home = items[0]?.path;

  return (
    <Routes>
      <Route element={<AdminLayout />}>
        {items.map((item) => (
          <Route key={item.path} path={item.path} element={PAGES[item.path]} />
        ))}
        {CHILD_PAGES.filter((child) => items.some((item) => item.path === child.parent)).map(
          (child) => (
            <Route key={child.path} path={child.path} element={child.element} />
          ),
        )}
        <Route
          path="*"
          element={home ? <Navigate to={home} replace /> : <p>{t('common.shell.noPage')}</p>}
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
