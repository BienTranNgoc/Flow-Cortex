import '@fontsource-variable/google-sans-flex';
import { setLocaleProvider } from '@flow-cortex/api-client';
import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { useTranslation } from 'react-i18next';
import { activeLocale } from './lib/i18n';
import { App } from './App';
import { initTheme } from './app/useTheme';

setLocaleProvider(activeLocale);

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found in index.html');
}

initTheme();

/** Reads the labels through `useTranslation` so the whole-app view follows a language switch. */
function RootErrorBoundary({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <ErrorBoundary
      variant="root"
      labels={{
        title: t('common.errorView.title'),
        message: t('common.errorView.message'),
        reload: t('common.errorView.reload'),
      }}
    >
      {children}
    </ErrorBoundary>
  );
}

createRoot(rootEl).render(
  <StrictMode>
    <RootErrorBoundary>
      <App />
    </RootErrorBoundary>
  </StrictMode>,
);
